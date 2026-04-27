package com.example.documentsigner.pades;

import com.example.documentsigner.exception.TimestampException;

import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Hashtable;

/**
 * PDFBox SignatureInterface implementation for PAdES-B signature generation.
 *
 * This class generates CMS (Cryptographic Message Syntax) signatures that conform
 * to the PAdES-B (Basic) profile as defined by ETSI EN 319 142-1.
 *
 * The signature includes:
 * - Digest algorithm: SHA-256
 * - Signature algorithm: SHA256withRSA
 * - Signed attributes: content-type, message-digest, signing-time, signing-certificate-v2
 * - Certificate chain for validation
 */
public class PadesSignatureInterface implements SignatureInterface {

    private static final Logger log = LoggerFactory.getLogger(PadesSignatureInterface.class);

    private final PrivateKey privateKey;
    private final Certificate[] certificateChain;
    private final X509Certificate signingCertificate;
    private final TimestampService timestampService;

    /**
     * Creates a PAdES-B signature interface (no timestamp).
     */
    public PadesSignatureInterface(PrivateKey privateKey, Certificate[] certificateChain) {
        this(privateKey, certificateChain, null);
    }

    /**
     * Creates a PAdES signature interface with optional TSA support.
     *
     * @param privateKey The private key for signing
     * @param certificateChain The full certificate chain (signing cert first)
     * @param timestampService Optional TSA client; if non-null, output is PAdES-T.
     *                         If the TSA call fails, falls back to PAdES-B with a warning.
     */
    public PadesSignatureInterface(PrivateKey privateKey, Certificate[] certificateChain,
                                   TimestampService timestampService) {
        this.privateKey = privateKey;
        this.certificateChain = certificateChain;
        this.signingCertificate = (X509Certificate) certificateChain[0];
        this.timestampService = timestampService;
    }

    /**
     * Signs the content provided by PDFBox.
     *
     * This method is called by PDFBox during the incremental save process.
     * The InputStream contains the PDF bytes covered by the ByteRange
     * (excluding the /Contents placeholder).
     *
     * @param content InputStream of bytes to sign (ByteRange content)
     * @return CMS signature bytes in DER encoding
     * @throws IOException if signing fails
     */
    @Override
    public byte[] sign(InputStream content) throws IOException {
        try {
            // Read all bytes from the content stream
            byte[] contentBytes = readAllBytes(content);

            // Create the CMS signature
            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();

            // Build the content signer with SHA256withRSA
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(privateKey);

            // Create signing certificate attribute (ESS SigningCertificateV2)
            // This is required for PAdES-B compliance
            Attribute signingCertAttr = createSigningCertificateAttribute();

            // Create signed attributes table with signing-certificate-v2
            Hashtable<ASN1ObjectIdentifier, Attribute> signedAttrs = new Hashtable<>();
            signedAttrs.put(signingCertAttr.getAttrType(), signingCertAttr);
            AttributeTable signedAttrTable = new AttributeTable(signedAttrs);

            // Build signer info generator with custom signed attributes
            SignerInfoGenerator signerInfoGenerator = new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder()
                    .setProvider("BC")
                    .build())
                .setSignedAttributeGenerator(new DefaultSignedAttributeTableGenerator(signedAttrTable))
                .build(contentSigner, signingCertificate);

            generator.addSignerInfoGenerator(signerInfoGenerator);

            // Add the certificate chain to the signature
            generator.addCertificates(new JcaCertStore(Arrays.asList(certificateChain)));

            // Generate detached CMS signature
            // The second parameter (false) means the content is not encapsulated
            CMSSignedData signedData = generator.generate(
                new CMSProcessableByteArray(contentBytes),
                false
            );

            byte[] padesB = signedData.getEncoded();

            // Optionally upgrade to PAdES-T by embedding a TSA timestamp.
            // Any failure falls back to PAdES-B (server clock) — never breaks signing.
            if (timestampService != null) {
                try {
                    return timestampService.addTimestamp(padesB);
                } catch (TimestampException e) {
                    log.warn("TSA timestamp failed; falling back to PAdES-B (local clock). Cause: {}",
                             e.getMessage());
                }
            }
            return padesB;

        } catch (Exception e) {
            throw new IOException("Failed to generate PAdES signature: " + e.getMessage(), e);
        }
    }

    /**
     * Creates the ESS signing-certificate-v2 attribute as required by PAdES-B.
     *
     * This attribute binds the signing certificate to the signature,
     * preventing certificate substitution attacks.
     */
    private Attribute createSigningCertificateAttribute() throws Exception {
        // Calculate SHA-256 hash of the signing certificate
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] certHash = digest.digest(signingCertificate.getEncoded());

        // Create ESSCertIDv2 with SHA-256 algorithm identifier
        AlgorithmIdentifier hashAlgorithm = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
        ESSCertIDv2 essCertId = new ESSCertIDv2(hashAlgorithm, certHash);

        // Create SigningCertificateV2 attribute
        SigningCertificateV2 signingCert = new SigningCertificateV2(new ESSCertIDv2[]{essCertId});

        // Wrap in CMS Attribute
        ASN1EncodableVector v = new ASN1EncodableVector();
        v.add(signingCert.toASN1Primitive());

        return new Attribute(
            new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.2.47"), // id-aa-signingCertificateV2
            new DERSet(v)
        );
    }

    /**
     * Reads all bytes from an InputStream.
     * Compatible with Java 8.
     */
    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }
}
