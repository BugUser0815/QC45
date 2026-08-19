package de.rothner.qc45;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Iterator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** TLS trust configuration for the native OCPP WSS client. */
public final class TlsSupport {
    private TlsSupport() {}

    public static void configure(String caFile, boolean insecure) throws Exception {
        if (insecure) {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, new TrustManager[] { new TrustAllManager() }, new SecureRandom());
            SSLContext.setDefault(context);
            System.err.println("[QC45] WARNING: OCPP TLS certificate verification DISABLED");
            return;
        }

        if (caFile != null && caFile.trim().length() > 0) {
            File file = new File(caFile.trim());
            if (!file.isFile()) throw new IllegalArgumentException("OCPP CA file not found: " + file);

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream in = new FileInputStream(file);
            Collection<? extends Certificate> certificates;
            try {
                certificates = cf.generateCertificates(in);
            } finally {
                in.close();
            }
            if (certificates == null || certificates.isEmpty()) {
                throw new IllegalArgumentException("No X.509 certificates found in OCPP CA file: " + file);
            }

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Iterator<? extends Certificate> it = certificates.iterator(); it.hasNext();) {
                Certificate cert = it.next();
                ks.setCertificateEntry("ocpp-ca-" + (++i), cert);
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, tmf.getTrustManagers(), new SecureRandom());
            SSLContext.setDefault(context);
            System.out.println("[QC45] OCPP TLS custom CA loaded: " + file.getAbsolutePath() + " (" + i + " certificate(s))");
            return;
        }

        System.out.println("[QC45] OCPP TLS using JVM system trust store");
    }

    private static final class TrustAllManager implements X509TrustManager {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
    }
}
