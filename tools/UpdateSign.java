import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.util.Base64;

/**
 * Signiert die Windows-Setups, damit App und Server nur echte Updates installieren.
 * Nutzt denselben Schlüssel wie die Android-Releases.
 *
 *   java tools/UpdateSign.java sign KEYSTORE ALIAS DATEI...   (Passwörter aus RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_PASSWORD)
 *   java tools/UpdateSign.java pubkey ZERTIFIKAT.pem          (öffentlicher Schlüssel für Updater.kt)
 *   java tools/UpdateSign.java verify DATEI...                (prüft DATEI.sig mit dem Schlüssel aus Updater.kt)
 *
 * Schreibt zu jeder Datei eine DATEI.sig mit der Signatur (SHA256withRSA, Base64).
 */
public class UpdateSign {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("pubkey")) {
            try (var in = new FileInputStream(args[1])) {
                var cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
                System.out.println(Base64.getEncoder().encodeToString(cert.getPublicKey().getEncoded()));
            }
            return;
        }
        if (args[0].equals("verify")) {
            var src = Files.readString(Path.of("jvm/src/io/github/phillhf/iptv/update/Updater.kt"));
            var m = java.util.regex.Pattern.compile("PUBLIC_KEY =(.*?)\\n\\s*\\n", java.util.regex.Pattern.DOTALL).matcher(src);
            if (!m.find()) throw new IllegalStateException("PUBLIC_KEY nicht gefunden");
            var b64 = m.group(1).replaceAll("\"\\s*\\+\\s*\"", "").replaceAll("[\\s\"]", "");
            var pub = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
            for (int i = 1; i < args.length; i++) {
                var s = Signature.getInstance("SHA256withRSA");
                s.initVerify(pub);
                s.update(Files.readAllBytes(Path.of(args[i])));
                if (!s.verify(Base64.getDecoder().decode(Files.readString(Path.of(args[i] + ".sig")).trim())))
                    throw new IllegalStateException("Signatur passt nicht: " + args[i]);
                System.out.println("echt: " + args[i]);
            }
            return;
        }
        var ks = KeyStore.getInstance(new java.io.File(args[1]), System.getenv("RELEASE_KEYSTORE_PASSWORD").toCharArray());
        var key = (PrivateKey) ks.getKey(args[2], System.getenv("RELEASE_KEY_PASSWORD").toCharArray());
        for (int i = 3; i < args.length; i++) {
            var s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(Files.readAllBytes(Path.of(args[i])));
            Files.writeString(Path.of(args[i] + ".sig"), Base64.getEncoder().encodeToString(s.sign()));
            System.out.println("signiert: " + args[i]);
        }
    }
}
