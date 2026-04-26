package com.example.documentsigner.pades;

import com.example.documentsigner.exception.TimestampException;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * RFC 3161 Timestamp (TSA) client for upgrading PAdES-B signatures to PAdES-T.
 *
 * Workflow:
 * 1. Hash the SignerInfo's signature value (encrypted digest) with SHA-256.
 * 2. Build a {@code TimeStampReq} (RFC 3161) with that hash + nonce + certReq=true.
 * 3. POST it to the TSA over HTTP with {@code application/timestamp-query}.
 * 4. Parse the {@code TimeStampResp}, validate against our request.
 * 5. Embed the resulting {@code TimeStampToken} in the CMS as unsigned attribute
 *    {@code id-aa-signatureTimeStampToken} (OID 1.2.840.113549.1.9.16.2.14).
 *
 * The augmented CMS is fully PAdES-T compliant (ETSI EN 319 142-1).
 */
public class TimestampService {

    private static final Logger log = LoggerFactory.getLogger(TimestampService.class);

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 15_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024; // 64 KB safety cap

    private final String tsaUrl;
    private final SecureRandom random = new SecureRandom();

    public TimestampService(String tsaUrl) {
        if (tsaUrl == null || tsaUrl.isEmpty()) {
            throw new IllegalArgumentException("tsaUrl must not be empty");
        }
        this.tsaUrl = tsaUrl;
    }

    /**
     * Augments an existing CMS signature with an RFC 3161 timestamp token.
     *
     * @param cmsBytes DER-encoded CMS SignedData (the PAdES-B output)
     * @return DER-encoded CMS SignedData with timestamp embedded as unsigned attribute
     * @throws TimestampException if any step fails — caller decides whether to fall back
     */
    public byte[] addTimestamp(byte[] cmsBytes) throws TimestampException {
        try {
            CMSSignedData cms = new CMSSignedData(cmsBytes);
            SignerInformationStore signers = cms.getSignerInfos();
            if (signers.size() == 0) {
                throw new TimestampException("CMS contains no SignerInfo");
            }
            SignerInformation signer = signers.getSigners().iterator().next();

            byte[] tstEncoded = requestTimestampToken(signer.getSignature());

            // Embed as unsigned attribute id-aa-signatureTimeStampToken
            Attribute tsAttr = new Attribute(
                PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                new DERSet(ASN1Primitive.fromByteArray(tstEncoded))
            );

            AttributeTable existing = signer.getUnsignedAttributes();
            AttributeTable updated;
            if (existing == null) {
                ASN1EncodableVector v = new ASN1EncodableVector();
                v.add(tsAttr);
                updated = new AttributeTable(v);
            } else {
                updated = existing.add(
                    PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                    new DERSet(ASN1Primitive.fromByteArray(tstEncoded))
                );
            }

            SignerInformation withTst = SignerInformation.replaceUnsignedAttributes(signer, updated);
            SignerInformationStore newStore =
                new SignerInformationStore(Collections.singletonList(withTst));
            CMSSignedData newCms = CMSSignedData.replaceSigners(cms, newStore);

            log.info("PAdES-T: timestamp embedded successfully (TSA: {})", tsaUrl);
            return newCms.getEncoded();

        } catch (TimestampException e) {
            throw e;
        } catch (Exception e) {
            throw new TimestampException("Failed to embed TSA timestamp: " + e.getMessage(), e);
        }
    }

    /**
     * Performs a complete RFC 3161 round-trip and returns the DER-encoded TimeStampToken.
     */
    private byte[] requestTimestampToken(byte[] signatureValue) throws TimestampException {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(signatureValue);

            BigInteger nonce = new BigInteger(64, random);
            TimeStampRequestGenerator gen = new TimeStampRequestGenerator();
            gen.setCertReq(true);
            TimeStampRequest req = gen.generate(TSPAlgorithms.SHA256, hash, nonce);

            byte[] respBytes = postToTsa(req.getEncoded());

            TimeStampResponse resp = new TimeStampResponse(respBytes);
            resp.validate(req); // throws if status != granted or nonce mismatch

            TimeStampToken token = resp.getTimeStampToken();
            if (token == null) {
                throw new TimestampException(
                    "TSA returned no token (status: " + resp.getStatusString() + ")");
            }

            return token.getEncoded();

        } catch (TimestampException e) {
            throw e;
        } catch (Exception e) {
            throw new TimestampException("TSA round-trip failed: " + e.getMessage(), e);
        }
    }

    private byte[] postToTsa(byte[] reqBytes) throws TimestampException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(tsaUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/timestamp-query");
            conn.setRequestProperty("Accept", "application/timestamp-reply");
            conn.getOutputStream().write(reqBytes);
            conn.getOutputStream().flush();

            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new TimestampException(
                    "TSA returned HTTP " + status + " from " + tsaUrl);
            }

            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int total = 0, n;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_RESPONSE_BYTES) {
                        throw new TimestampException(
                            "TSA response exceeded " + MAX_RESPONSE_BYTES + " bytes");
                    }
                    baos.write(buf, 0, n);
                }
                return baos.toByteArray();
            }

        } catch (TimestampException e) {
            throw e;
        } catch (Exception e) {
            throw new TimestampException("TSA HTTP request failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
