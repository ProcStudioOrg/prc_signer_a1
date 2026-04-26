package com.example.documentsigner.pades;

import com.example.documentsigner.pades.dto.RevocationStatus;
import com.example.documentsigner.pades.dto.TsaInfo;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.util.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for deeper signature inspection beyond raw CMS verify:
 *  - Extract the embedded RFC 3161 timestamp token (PAdES-T).
 *  - Best-effort OCSP revocation check against the cert's AIA OCSP URL.
 *
 * Soft-fail by design — network/parser problems return UNKNOWN/ERROR
 * rather than throwing, so the upper layer can decide how to surface them.
 */
public class SignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(SignatureValidator.class);

    private static final int OCSP_CONNECT_TIMEOUT_MS = 5_000;
    private static final int OCSP_READ_TIMEOUT_MS    = 10_000;

    /**
     * Decodes the {@code id-aa-signatureTimeStampToken} unsigned attribute
     * if present and validates the TST signature against its embedded TSA cert.
     *
     * @return {@code null} if the SignerInfo carries no timestamp attribute
     */
    public TsaInfo extractTimestamp(SignerInformation signer) {
        AttributeTable unsigned = signer.getUnsignedAttributes();
        if (unsigned == null) return null;

        Attribute tsAttr = unsigned.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
        if (tsAttr == null) return null;

        try {
            ASN1Encodable tokenEncoded = tsAttr.getAttrValues().getObjectAt(0);
            ContentInfo contentInfo = ContentInfo.getInstance(tokenEncoded);
            TimeStampToken tst = new TimeStampToken(contentInfo);

            String tsaName = null;
            boolean tokenValid = false;

            // The TSA's signing cert is in the TST's certificates store
            Store<X509CertificateHolder> certStore = tst.getCertificates();
            Collection<X509CertificateHolder> tsaMatches = certStore.getMatches(tst.getSID());
            if (!tsaMatches.isEmpty()) {
                X509CertificateHolder tsaCertHolder = tsaMatches.iterator().next();
                X509Certificate tsaCert = new JcaX509CertificateConverter()
                    .setProvider("BC")
                    .getCertificate(tsaCertHolder);
                tsaName = extractCN(tsaCert.getSubjectX500Principal().getName());
                try {
                    tst.validate(new JcaSimpleSignerInfoVerifierBuilder()
                        .setProvider("BC")
                        .build(tsaCert));
                    tokenValid = true;
                } catch (Exception e) {
                    log.warn("TST cryptographic validation failed: {}", e.getMessage());
                }
            }

            return new TsaInfo(tst.getTimeStampInfo().getGenTime(), tsaName, tokenValid);

        } catch (Exception e) {
            log.warn("Failed to decode timestamp token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Performs an OCSP request against the responder URL listed in the cert's
     * Authority Information Access extension.
     *
     * Requires both the end-entity certificate and its issuer (for CertID).
     * Returns {@link RevocationStatus#notChecked(String)} when prerequisites are missing.
     */
    public RevocationStatus checkOcsp(X509Certificate cert, X509Certificate issuer) {
        if (cert == null) {
            return RevocationStatus.notChecked("No certificate to check");
        }
        if (issuer == null) {
            return RevocationStatus.notChecked("Issuer certificate not available — chain too short");
        }

        String ocspUrl = extractOcspUrl(cert);
        if (ocspUrl == null) {
            return RevocationStatus.notChecked("Certificate has no OCSP URL in AIA extension");
        }

        try {
            DigestCalculatorProvider digestProv = new JcaDigestCalculatorProviderBuilder()
                .setProvider("BC")
                .build();

            CertificateID certId = new CertificateID(
                digestProv.get(CertificateID.HASH_SHA1),
                new JcaX509CertificateHolder(issuer),
                cert.getSerialNumber()
            );

            OCSPReqBuilder reqBuilder = new OCSPReqBuilder();
            reqBuilder.addRequest(certId);
            OCSPReq ocspReq = reqBuilder.build();

            byte[] respBytes = postOcspRequest(ocspUrl, ocspReq.getEncoded());

            OCSPResp ocspResp = new OCSPResp(respBytes);
            if (ocspResp.getStatus() != OCSPResp.SUCCESSFUL) {
                return RevocationStatus.unknown(
                    "OCSP responder returned status code " + ocspResp.getStatus());
            }

            BasicOCSPResp basicResp = (BasicOCSPResp) ocspResp.getResponseObject();
            SingleResp[] singleResps = basicResp.getResponses();
            if (singleResps.length == 0) {
                return RevocationStatus.unknown("OCSP response had no SingleResponse entries");
            }

            CertificateStatus certStatus = singleResps[0].getCertStatus();
            if (certStatus == null) {
                // BC API: null == GOOD
                return RevocationStatus.good("OCSP responder confirmed GOOD (" + ocspUrl + ")");
            }
            if (certStatus instanceof RevokedStatus) {
                RevokedStatus rev = (RevokedStatus) certStatus;
                return RevocationStatus.revoked(
                    "OCSP responder confirmed REVOKED at " + rev.getRevocationTime(),
                    rev.getRevocationTime());
            }
            return RevocationStatus.unknown("OCSP returned status: " + certStatus.getClass().getSimpleName());

        } catch (Exception e) {
            log.warn("OCSP check failed for cert serial {}: {}",
                     cert.getSerialNumber(), e.getMessage());
            return RevocationStatus.error("OCSP check failed: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────

    private String extractOcspUrl(X509Certificate cert) {
        try {
            byte[] aiaExtBytes = cert.getExtensionValue(Extension.authorityInfoAccess.getId());
            if (aiaExtBytes == null) return null;

            ASN1Sequence seq = (ASN1Sequence) org.bouncycastle.asn1.ASN1Primitive
                .fromByteArray(
                    org.bouncycastle.asn1.ASN1OctetString
                        .getInstance(org.bouncycastle.asn1.ASN1Primitive.fromByteArray(aiaExtBytes))
                        .getOctets()
                );
            AuthorityInformationAccess aia = AuthorityInformationAccess.getInstance(seq);
            for (AccessDescription ad : aia.getAccessDescriptions()) {
                if (X509ObjectIdentifiers.id_ad_ocsp.equals(ad.getAccessMethod())) {
                    GeneralName name = ad.getAccessLocation();
                    if (name.getTagNo() == GeneralName.uniformResourceIdentifier) {
                        return name.getName().toString();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to extract OCSP URL from cert: {}", e.getMessage());
            return null;
        }
    }

    private byte[] postOcspRequest(String url, byte[] body) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(OCSP_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(OCSP_READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/ocsp-request");
            conn.setRequestProperty("Accept", "application/ocsp-response");
            conn.getOutputStream().write(body);

            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("OCSP responder returned HTTP " + status);
            }

            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                return baos.toByteArray();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String extractCN(String dn) {
        if (dn == null) return null;
        Matcher m = Pattern.compile("CN=([^,]+)").matcher(dn);
        if (m.find()) {
            String value = m.group(1);
            if (value.contains(":")) value = value.split(":")[0];
            return value.trim();
        }
        return null;
    }
}
