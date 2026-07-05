package com.example.documentsigner;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.Store;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class DocumentSigner {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public byte[] signDocument(byte[] document, String pfxPath, String pfxPassword) throws Exception {
        return signDocumentWithStream(document, new FileInputStream(pfxPath), pfxPassword);
    }

    public byte[] signDocumentWithCertBytes(byte[] document, byte[] certBytes, String pfxPassword) throws Exception {
        return signDocumentWithStream(document, new ByteArrayInputStream(certBytes), pfxPassword);
    }

    private byte[] signDocumentWithStream(byte[] document, InputStream certStream, String pfxPassword) throws Exception {
        // Load the PFX/PKCS12 keystore
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        char[] pw = pfxPassword.toCharArray();
        keystore.load(certStream, pw);

        // Get the private key and certificate
        String alias = keystore.aliases().nextElement();
        PrivateKey privateKey = (PrivateKey) keystore.getKey(alias, pw);
        com.example.documentsigner.util.Sensitive.wipe(pw); // senha não é mais necessária
        Certificate[] certificateChain = keystore.getCertificateChain(alias);
        X509Certificate signingCert = (X509Certificate) certificateChain[0];

        // Create certificate store with full chain
        List<Certificate> certList = new ArrayList<>();
        for (Certificate cert : certificateChain) {
            certList.add(cert);
        }
        Store certStore = new JcaCertStore(certList);

        // Create CMS SignedData generator
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();

        // Add signing parameters
        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(privateKey);

        CMSSignedDataGenerator cmsGenerator = new CMSSignedDataGenerator();
        cmsGenerator.addSignerInfoGenerator(
            new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder()
                    .setProvider("BC")
                    .build())
            .build(contentSigner, signingCert));

        // Add certificates to the signature
        cmsGenerator.addCertificates(certStore);

        // Create signed data
        CMSTypedData cmsData = new CMSProcessableByteArray(document);
        CMSSignedData signedData = cmsGenerator.generate(cmsData, true);

        return signedData.getEncoded();
    }

    public boolean verifySignature(byte[] signedData, byte[] originalData) throws Exception {
        CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(originalData), signedData);
        Store<X509CertificateHolder> certStore = cms.getCertificates();
        SignerInformationStore signers = cms.getSignerInfos();
        
        for (SignerInformation signer : signers.getSigners()) {
            Collection<X509CertificateHolder> certCollection = certStore.getMatches(signer.getSID());
            X509CertificateHolder cert = certCollection.iterator().next();

            try {
                if (!signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                        .setProvider("BC")
                        .build(new JcaX509CertificateConverter().getCertificate(cert)))) {
                    return false;
                }
            } catch (CMSException e) {
                // BouncyCastle throws (não retorna false) quando o digest não bate
                // — ex.: documento adulterado ou assinatura de outro arquivo. Isso é
                // um RESULTADO de verificação (assinatura inválida), não erro interno.
                return false;
            }
        }
        return true;
    }
}