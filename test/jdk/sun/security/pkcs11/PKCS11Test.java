/*
 * Copyright (c) 2003, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

// common infrastructure for SunPKCS11 tests

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPairGenerator;
import java.security.NoSuchProviderException;
import java.security.Policy;
import java.security.Provider;
import java.security.ProviderException;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

import jdk.test.lib.Platform;
import jdk.test.lib.artifacts.Artifact;
import jdk.test.lib.artifacts.ArtifactResolver;
import jdk.test.lib.artifacts.ArtifactResolverException;
import jtreg.SkippedException;

public abstract class PKCS11Test {

    static final Properties props = System.getProperties();
    static final String PKCS11 = "PKCS11";
    // directory of the test source
    static final String BASE = System.getProperty("test.src", ".");
    static final String TEST_CLASSES = System.getProperty("test.classes", ".");
    static final char SEP = File.separatorChar;

    // Version of the NSS artifact. This coincides with the version of
    // the NSS version
    private static final String NSS_BUNDLE_VERSION = "3.91";
    private static final String NSSLIB = "jpg.tests.jdk.nsslib";

    // directory corresponding to BASE in the /closed hierarchy
    static final String CLOSED_BASE;
    private static final String DEFAULT_POLICY = BASE + SEP + ".." + SEP + "policy";
    private static final String PKCS11_REL_PATH = "sun/security/pkcs11";
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    static double nss_version = -1;
    static ECCState nss_ecc_status = ECCState.Basic;

    // The NSS library we need to search for in getNSSLibDir()
    // Default is "libsoftokn3.so", listed as "softokn3"
    // The other is "libnss3.so", listed as "nss3".
    static String nss_library = "softokn3";

    // NSS versions of each library.  It is simpler to keep nss_version
    // for quick checking for generic testing than many if-else statements.
    static double softoken3_version = -1;
    static double nss3_version = -1;
    static Provider pkcs11 = newPKCS11Provider();
    private static String PKCS11_BASE;
    private static Map<String, String[]> osMap;

    static {
        // hack
        String absBase = new File(BASE).getAbsolutePath();
        int k = absBase.indexOf(SEP + "test" + SEP + "jdk" + SEP);
        if (k < 0) k = 0;
        String p1 = absBase.substring(0, k);
        String p2 = absBase.substring(k);
        CLOSED_BASE = p1 + "/../closed" + p2;

        // set it as a system property to make it available in policy file
        System.setProperty("closed.base", CLOSED_BASE);
    }

    static {
        try {
            PKCS11_BASE = getBase();
        } catch (Exception e) {
            // ignore
        }
    }

    private boolean enableSM = false;

    // Utility methods
    // Used to backport HexFormat from 17.
    protected static byte[] HexToBytes(String hexVal) {
        if (hexVal == null) return new byte[0];
        byte[] result = new byte[hexVal.length()/2];
        for (int i = 0; i < result.length; i++) {
            // 2 characters at a time
            String byteVal = hexVal.substring(2*i, 2*i +2);
            result[i] = Integer.valueOf(byteVal, 16).byteValue();
        }
        return result;
    }
    private static void byte2hex(byte b, StringBuffer buf) {
        char[] hexChars = { '0', '1', '2', '3', '4', '5', '6', '7', '8',
                            '9', 'A', 'B', 'C', 'D', 'E', 'F' };
        int high = ((b & 0xf0) >> 4);
        int low = (b & 0x0f);
        buf.append(hexChars[high]);
        buf.append(hexChars[low]);
    }
    protected static String toHexString(byte[] block) {
        StringBuffer buf = new StringBuffer();

        int len = block.length;

        for (int i = 0; i < len; i++) {
             byte2hex(block[i], buf);
             if (i < len-1) {
                 buf.append(":");
             }
        }
        return buf.toString();
    }

    public static Provider newPKCS11Provider() {
        ServiceLoader<Provider> sl = ServiceLoader.load(java.security.Provider.class);
        Iterator<Provider> iter = sl.iterator();
        Provider p = null;
        boolean found = false;
        while (iter.hasNext()) {
            try {
                p = iter.next();
                if (p.getName().equals("SunPKCS11")) {
                    found = true;
                    break;
                }
            } catch (Exception | ServiceConfigurationError e) {
                // ignore and move on to the next one
            }
        }
        // Nothing found through ServiceLoader; fall back to reflection
        if (!found) {
            try {
                Class<?> clazz = Class.forName("sun.security.pkcs11.SunPKCS11");
                p = (Provider) clazz.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        return p;
    }

    /*
     * Use Solaris SPARC 11.2 or later to avoid an intermittent failure
     * when running SunPKCS11-Solaris (8044554)
     */
    static boolean isBadSolarisSparc(Provider p) {
        if ("SunPKCS11-Solaris".equals(p.getName()) && badSolarisSparc) {
            System.out.println("SunPKCS11-Solaris provider requires " +
                "Solaris SPARC 11.2 or later, skipping");
            return true;
        }
        return false;
    }

    // Return the static test SunPKCS11 provider configured with the specified config file
    static Provider getSunPKCS11(String config) throws Exception {
        return getSunPKCS11(config, pkcs11);
    }

    // Return the Provider p configured with the specified config file
    static Provider getSunPKCS11(String config, Provider p) throws Exception {
        if (p == null) {
            throw new NoSuchProviderException("No PKCS11 provider available");
        }
        return p.configure(config);
    }

    public static void main(PKCS11Test test) throws Exception {
        main(test, null);
    }

    public static void main(PKCS11Test test, String[] args) throws Exception {
        if (args != null) {
            if (args.length > 0) {
                if ("sm".equals(args[0])) {
                    test.enableSM = true;
                } else {
                    throw new RuntimeException("Unknown Command, use 'sm' as "
                            + "first argument to enable security manager");
                }
            }
            if (test.enableSM) {
                System.setProperty("java.security.policy",
                        (args.length > 1) ? BASE + SEP + args[1]
                                : DEFAULT_POLICY);
            }
        }

        Provider[] oldProviders = Security.getProviders();
        try {
            System.out.println("Beginning test run " + test.getClass().getName() + "...");
            boolean skippedDefault = false;
            boolean skippedNSS = false;
            boolean skippedDeimos = false;

            // Use separate try-catch for each test to allow all test run
            try {
                testDefault(test);
            } catch (SkippedException se) {
                System.out.println("testDefault: Skipped");
                skippedDefault = true;
                se.printStackTrace(System.out);
            }

            try {
                testNSS(test);
            } catch (SkippedException se) {
                System.out.println("testNSS: Skipped");
                skippedNSS = true;
                se.printStackTrace(System.out);
            }

            try {
                testDeimos(test);
            } catch (SkippedException se) {
                System.out.println("testDeimos: Skipped");
                skippedDeimos = true;
                se.printStackTrace(System.out);
            }

            if (skippedDefault && skippedNSS && skippedDeimos) {
                throw new SkippedException("All tests are skipped, check logs");
            }

        } finally {
            // NOTE: Do not place a 'return' in any finally block
            // as it will suppress exceptions and hide test failures.
            Provider[] newProviders = Security.getProviders();
            boolean found = true;
            // Do not restore providers if nothing changed. This is especailly
            // useful for ./Provider/Login.sh, where a SecurityManager exists.
            if (oldProviders.length == newProviders.length) {
                found = false;
                for (int i = 0; i < oldProviders.length; i++) {
                    if (oldProviders[i] != newProviders[i]) {
                        found = true;
                        break;
                    }
                }
            }
            if (found) {
                for (Provider p : newProviders) {
                    Security.removeProvider(p.getName());
                }
                for (Provider p : oldProviders) {
                    Security.addProvider(p);
                }
            }
        }
    }

    public static void testDeimos(PKCS11Test test) throws Exception {
        System.out.println("===> testDeimos: Starting test run");
        if ("true".equals(System.getProperty("NO_DEIMOS"))) {
            System.out.println("Skip Deimos software as test configured with NO_DEIMOS");
            return;
        }

        if (!new File("/opt/SUNWconn/lib/libpkcs11.so").isFile()) {
            throw new SkippedException("testDeimos: \"/opt/SUNWconn/lib/libpkcs11.so\" " +
                    "file required for Deimos not found");
        }

        String base = getBase();
        String p11config = base + SEP + "nss" + SEP + "p11-deimos.txt";
        Provider p = getSunPKCS11(p11config);
        test.premain(p);
        System.out.println("testDeimos: Completed");
    }

    // Run test for default configured PKCS11 providers (if any)
    public static void testDefault(PKCS11Test test) throws Exception {
        System.out.println("===> testDefault: Starting test run");
        boolean foundPKCS11 = false;

        if ("true".equals(System.getProperty("NO_DEFAULT"))) {
            System.out.println("Skip default provider as test configured with NO_DEFAULT");
            return;
        }

        Provider[] providers = Security.getProviders();
        for (Provider p : providers) {
            if (p.getName().startsWith("SunPKCS11-")) {
                foundPKCS11 = true;
                test.premain(p);
            }
        }

        if (!foundPKCS11) {
            throw new SkippedException("testDefault: Skip default test as SunPKCS11 " +
                    "provider is not configured");
        }

        System.out.println("testDefault: Completed");
    }

    public static String getBase() throws Exception {
        if (PKCS11_BASE != null) {
            return PKCS11_BASE;
        }
        File cwd = new File(System.getProperty("test.src", ".")).getCanonicalFile();
        while (true) {
            File file = new File(cwd, "TEST.ROOT");
            if (file.isFile()) {
                break;
            }
            cwd = cwd.getParentFile();
            if (cwd == null) {
                throw new RuntimeException("Test root directory not found");
            }
        }
        PKCS11_BASE = new File(cwd, PKCS11_REL_PATH.replace('/', SEP)).getAbsolutePath();
        return PKCS11_BASE;
    }

    public static String getNSSLibDir() throws Exception {
        return getNSSLibDir(nss_library);
    }

    static String getNSSLibDir(String library) throws Exception {
        Path libPath = getNSSLibPath(library);
        if (libPath == null) {
            return null;
        }

        String libDir = String.valueOf(libPath.getParent()) + File.separatorChar;
        System.out.println("nssLibDir: " + libDir);
        System.setProperty("pkcs11test.nss.libdir", libDir);
        return libDir;
    }

    private static Path getNSSLibPath() throws Exception {
        return getNSSLibPath(nss_library);
    }

    static Path getNSSLibPath(String library) throws Exception {
        String osid = getOsId();
        Path libraryName = Path.of(System.mapLibraryName(library));
        Path nssLibPath = fetchNssLib(osid, libraryName);
        if (nssLibPath == null) {
            throw new SkippedException("Warning: unsupported OS: " + osid
                    + ", please initialize NSS library location, skipping test");
        }
        return nssLibPath;
    }

    private static String getOsId() {
        String osName = props.getProperty("os.name");
        if (osName.startsWith("Win")) {
            osName = "Windows";
        } else if (osName.equals("Mac OS X")) {
            osName = "MacOSX";
        }
        return osName + "-" + props.getProperty("os.arch") + "-"
                + props.getProperty("sun.arch.data.model");
    }

    static boolean isBadNSSVersion(Provider p) {
        double nssVersion = getNSSVersion();
        if (isNSS(p) && nssVersion >= 3.11 && nssVersion < 3.12) {
            System.out.println("NSS 3.11 has a DER issue that recent " +
                    "version do not, skipping");
            return true;
        }
        return false;
    }

    protected static void safeReload(String lib) {
        try {
            System.load(lib);
        } catch (UnsatisfiedLinkError e) {
            if (e.getMessage().contains("already loaded")) {
                return;
            }
        }
    }

    static boolean loadNSPR(String libdir) {
        // load NSS softoken dependencies in advance to avoid resolver issues
        String dir = libdir.endsWith(File.separator) ? libdir : libdir + File.separator;
        safeReload(dir + System.mapLibraryName("nspr4"));
        safeReload(dir + System.mapLibraryName("plc4"));
        safeReload(dir + System.mapLibraryName("plds4"));
        safeReload(dir + System.mapLibraryName("sqlite3"));
        safeReload(dir + System.mapLibraryName("nssutil3"));
        return true;
    }

    // Check the provider being used is NSS
    public static boolean isNSS(Provider p) {
        return p.getName().equalsIgnoreCase("SUNPKCS11-NSS");
    }

    static double getNSSVersion() {
        if (nss_version == -1)
            getNSSInfo();
        return nss_version;
    }

    static ECCState getNSSECC() {
        if (nss_version == -1)
            getNSSInfo();
        return nss_ecc_status;
    }

    public static double getLibsoftokn3Version() {
        if (softoken3_version == -1)
            return getNSSInfo("softokn3");
        return softoken3_version;
    }

    public static double getLibnss3Version() {
        if (nss3_version == -1)
            return getNSSInfo("nss3");
        return nss3_version;
    }

    /* Read the library to find out the verison */
    static void getNSSInfo() {
        getNSSInfo(nss_library);
    }

    // Try to parse the version for the specified library.
    // Assuming the library contains either of the following patterns:
    // $Header: NSS <version>
    // Version: NSS <version>
    // Here, <version> stands for NSS version.
    static double getNSSInfo(String library) {
        // look for two types of headers in NSS libraries
        String nssHeader1 = "$Header: NSS";
        String nssHeader2 = "Version: NSS";
        boolean found = false;
        String s = null;
        int i = 0;
        Path libfile = null;

        if (library.compareTo("softokn3") == 0 && softoken3_version > -1)
            return softoken3_version;
        if (library.compareTo("nss3") == 0 && nss3_version > -1)
            return nss3_version;

        try {
            libfile = getNSSLibPath();
            if (libfile == null) {
                return 0.0;
            }
            try (InputStream is = Files.newInputStream(libfile)) {
                byte[] data = new byte[1000];
                int read = 0;

                while (is.available() > 0) {
                    if (read == 0) {
                        read = is.read(data, 0, 1000);
                    } else {
                        // Prepend last 100 bytes in case the header was split
                        // between the reads.
                        System.arraycopy(data, 900, data, 0, 100);
                        read = 100 + is.read(data, 100, 900);
                    }

                    s = new String(data, 0, read, StandardCharsets.US_ASCII);
                    i = s.indexOf(nssHeader1);
                    if (i > 0 || (i = s.indexOf(nssHeader2)) > 0) {
                        found = true;
                        // If the nssHeader is before 920 we can break, otherwise
                        // we may not have the whole header so do another read.  If
                        // no bytes are in the stream, that is ok, found is true.
                        if (i < 920) {
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (!found) {
            System.out.println("lib" + library +
                    " version not found, set to 0.0: " + libfile);
            nss_version = 0.0;
            return nss_version;
        }

        // the index after whitespace after nssHeader
        int afterheader = s.indexOf("NSS", i) + 4;
        StringBuilder version = new StringBuilder(String.valueOf(s.charAt(afterheader)));
        for (char c = s.charAt(++afterheader);
             c == '.' || (c >= '0' && c <= '9');
             c = s.charAt(++afterheader)) {
            version.append(c);
        }

        // If a "dot dot" release, strip the extra dots for double parsing
        String[] dot = version.toString().split("\\.");
        if (dot.length > 2) {
            version = new StringBuilder(dot[0] + "." + dot[1]);
            for (int j = 2; dot.length > j; j++) {
                version.append(dot[j]);
            }
        }

        // Convert to double for easier version value checking
        try {
            nss_version = Double.parseDouble(version.toString());
        } catch (NumberFormatException e) {
            System.out.println("===== Content start =====");
            System.out.println(s);
            System.out.println("===== Content end =====");
            System.out.println("Failed to parse lib" + library +
                    " version. Set to 0.0");
            e.printStackTrace();
        }

        System.out.print("library: " + library + ", version: " + version + ".  ");

        // Check for ECC
        if (s.indexOf("Basic") > 0) {
            nss_ecc_status = ECCState.Basic;
            System.out.println("ECC Basic.");
        } else if (s.indexOf("Extended") > 0) {
            nss_ecc_status = ECCState.Extended;
            System.out.println("ECC Extended.");
        } else {
            System.out.println("ECC None.");
        }

        if (library.compareTo("softokn3") == 0) {
            softoken3_version = nss_version;
        } else if (library.compareTo("nss3") == 0) {
            nss3_version = nss_version;
        }

        return nss_version;
    }

    // Used to set the nss_library file to search for libsoftokn3.so
    public static void useNSS() {
        nss_library = "nss3";
    }

    // Run NSS testing on a Provider p configured with test nss config
    public static void testNSS(PKCS11Test test) throws Exception {
        System.out.println("===> testNSS: Starting test run");
        String nssConfig = getNssConfig();
        if (nssConfig == null) {
            throw new SkippedException("testNSS: Problem loading NSS libraries");
        }

        Provider p = getSunPKCS11(nssConfig);
        test.premain(p);
        System.out.println("testNSS: Completed");
    }

    public static String getNssConfig() throws Exception {
        String libdir = getNSSLibDir();
        if (libdir == null) {
            return null;
        }

        if (!loadNSPR(libdir)) {
            return null;
        }

        String base = getBase();

        String libfile = libdir + System.mapLibraryName(nss_library);

        String customDBdir = System.getProperty("CUSTOM_DB_DIR");
        String dbdir = (customDBdir != null) ?
                customDBdir :
                base + SEP + "nss" + SEP + "db";
        // NSS always wants forward slashes for the config path
        dbdir = dbdir.replace('\\', '/');

        String customConfig = System.getProperty("CUSTOM_P11_CONFIG");
        String customConfigName = System.getProperty("CUSTOM_P11_CONFIG_NAME", "p11-nss.txt");
        System.setProperty("pkcs11test.nss.lib", libfile);
        System.setProperty("pkcs11test.nss.db", dbdir);
        return (customConfig != null) ?
                customConfig :
                base + SEP + "nss" + SEP + customConfigName;
    }

    // Generate a vector of supported elliptic curves of a given provider
    static List<ECParameterSpec> getKnownCurves(Provider p) throws Exception {
        int index;
        int begin;
        int end;
        String curve;

        List<ECParameterSpec> results = new ArrayList<>();
        // Get Curves to test from SunEC.
        String kcProp = Security.getProvider("SunEC").
                getProperty("AlgorithmParameters.EC SupportedCurves");

        if (kcProp == null) {
            throw new RuntimeException(
                    "\"AlgorithmParameters.EC SupportedCurves property\" not found");
        }

        System.out.println("Finding supported curves using list from SunEC\n");
        index = 0;
        for (; ; ) {
            // Each set of curve names is enclosed with brackets.
            begin = kcProp.indexOf('[', index);
            end = kcProp.indexOf(']', index);
            if (begin == -1 || end == -1) {
                break;
            }

            /*
             * Each name is separated by a comma.
             * Just get the first name in the set.
             */
            index = end + 1;
            begin++;
            end = kcProp.indexOf(',', begin);
            if (end == -1) {
                // Only one name in the set.
                end = index - 1;
            }

            curve = kcProp.substring(begin, end);
            getSupportedECParameterSpec(curve, p)
                    .ifPresent(spec -> results.add(spec));
        }

        if (results.size() == 0) {
            throw new RuntimeException("No supported EC curves found");
        }

        return results;
    }

    static Optional<ECParameterSpec> getSupportedECParameterSpec(String curve,
                                                                 Provider p) throws Exception {
        ECParameterSpec e = getECParameterSpec(p, curve);
        System.out.print("\t " + curve + ": ");
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", p);
            kpg.initialize(e);
            kpg.generateKeyPair();
            System.out.println("Supported");
            return Optional.of(e);
        } catch (ProviderException ex) {
            System.out.println("Unsupported: PKCS11: " +
                    ex.getCause().getMessage());
            return Optional.empty();
        } catch (InvalidAlgorithmParameterException ex) {
            System.out.println("Unsupported: Key Length: " +
                    ex.getMessage());
            return Optional.empty();
        }
    }

    private static ECParameterSpec getECParameterSpec(Provider p, String name)
            throws Exception {

        AlgorithmParameters parameters =
                AlgorithmParameters.getInstance("EC", p);

        parameters.init(new ECGenParameterSpec(name));

        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private final static char[] hexDigits = "0123456789abcdef".toCharArray();

    private static final String distro = distro();

    static final boolean badSolarisSparc =
            System.getProperty("os.name").equals("SunOS") &&
            System.getProperty("os.arch").equals("sparcv9") &&
            System.getProperty("os.version").compareTo("5.11") <= 0 &&
            getDistro().compareTo("11.2") < 0;

    public static String toString(byte[] b) {
        if (b == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            int k = b[i] & 0xff;
            if (i != 0) {
                sb.append(':');
            }
            sb.append(HEX_DIGITS[k >>> 4]);
            sb.append(HEX_DIGITS[k & 0xf]);
        }
        return sb.toString();
    }

    public static byte[] parse(String s) {
        if (s.equals("(null)")) {
            return null;
        }
        try {
            int n = s.length();
            ByteArrayOutputStream out = new ByteArrayOutputStream(n / 3);
            StringReader r = new StringReader(s);
            while (true) {
                int b1 = nextNibble(r);
                if (b1 < 0) {
                    break;
                }
                int b2 = nextNibble(r);
                if (b2 < 0) {
                    throw new RuntimeException("Invalid string " + s);
                }
                int b = (b1 << 4) | b2;
                out.write(b);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int nextNibble(StringReader r) throws IOException {
        while (true) {
            int ch = r.read();
            if (ch == -1) {
                return -1;
            } else if ((ch >= '0') && (ch <= '9')) {
                return ch - '0';
            } else if ((ch >= 'a') && (ch <= 'f')) {
                return ch - 'a' + 10;
            } else if ((ch >= 'A') && (ch <= 'F')) {
                return ch - 'A' + 10;
            }
        }
    }

    /**
     * Returns supported algorithms of specified type.
     */
    static List<String> getSupportedAlgorithms(String type, String alg,
                                               Provider p) {
        // prepare a list of supported algorithms
        List<String> algorithms = new ArrayList<>();
        Set<Provider.Service> services = p.getServices();
        for (Provider.Service service : services) {
            if (service.getType().equals(type)
                    && service.getAlgorithm().startsWith(alg)) {
                algorithms.add(service.getAlgorithm());
            }
        }
        return algorithms;
    }

    /**
     * Get the identifier for the operating system distribution
     */
    static String getDistro() {
        return distro;
    }

    private static String distro() {
        if (props.getProperty("os.name").equals("SunOS")) {
            try (BufferedReader in =
                         new BufferedReader(new InputStreamReader(
                                 Runtime.getRuntime().exec("uname -v").getInputStream()))) {

                return in.readLine();
            } catch (Exception e) {
                throw new RuntimeException("Failed to determine distro.", e);
            }
        } else {
            // Not used outside Solaris
            return null;
        }
    }

    static byte[] generateData(int length) {
        byte data[] = new byte[length];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        return data;
    }

    private static Path fetchNssLib(String osId, Path libraryName) {
        switch (osId) {
            case "Windows-amd64-64":
                return fetchNssLib(WINDOWS_X64.class, libraryName);

            case "MacOSX-x86_64-64":
                return fetchNssLib(MACOSX_X64.class, libraryName);

            case "MacOSX-aarch64-64":
                return fetchNssLib(MACOSX_AARCH64.class, libraryName);

            case "Linux-amd64-64":
                if (Platform.isOracleLinux7()) {
                    throw new SkippedException("Skipping Oracle Linux prior to v8");
                } else {
                    return fetchNssLib(LINUX_X64.class, libraryName);
                }

            case "Linux-aarch64-64":
                if (Platform.isOracleLinux7()) {
                    throw new SkippedException("Skipping Oracle Linux prior to v8");
                } else {
                    return fetchNssLib(LINUX_AARCH64.class, libraryName);
                }
            default:
                return null;
        }
    }

    private static Path fetchNssLib(Class<?> clazz, Path libraryName) {
        Path path = null;
        try {
            Path p = ArtifactResolver.resolve(clazz).entrySet().stream()
                    .findAny().get().getValue();
            path = findNSSLibrary(p, libraryName);
        } catch (ArtifactResolverException | IOException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                System.out.println("Cannot resolve artifact, "
                        + "please check if JIB jar is present in classpath.");
            } else {
                throw new RuntimeException("Fetch artifact failed: " + clazz
                        + "\nPlease make sure the artifact is available.");
            }
        }
        Policy.setPolicy(null); // Clear the policy created by JIB if any
        return path;
    }

    private static Path findNSSLibrary(Path path, Path libraryName) throws IOException {
        try(Stream<Path> files = Files.find(path, 10,
                (tp, attr) -> tp.getFileName().equals(libraryName))) {

            return files.findAny()
                        .orElseThrow(() -> new SkippedException(
                        "NSS library \"" + libraryName + "\" was not found in " + path));
        }
    }

    public abstract void main(Provider p) throws Exception;

    protected boolean skipTest(Provider p) {
        return false;
    }

    private void premain(Provider p) throws Exception {
        if (skipTest(p)) {
            return;
        }

        // set a security manager and policy before a test case runs,
        // and disable them after the test case finished
        try {
            if (enableSM) {
                System.setSecurityManager(new SecurityManager());
            }
            long start = System.currentTimeMillis();
            System.out.printf(
                    "Running test with provider %s (security manager %s) ...%n",
                    p.getName(), enableSM ? "enabled" : "disabled");
            main(p);
            long stop = System.currentTimeMillis();
            System.out.println("Completed test with provider " + p.getName() +
                    " (" + (stop - start) + " ms).");
        } finally {
            if (enableSM) {
                System.setSecurityManager(null);
            }
        }
    }

    // Check support for a curve with a provided Vector of EC support
    boolean checkSupport(List<ECParameterSpec> supportedEC,
                         ECParameterSpec curve) {
        for (ECParameterSpec ec : supportedEC) {
            if (ec.equals(curve)) {
                return true;
            }
        }
        return false;
    }

    <T> T[] concat(T[] a, T[] b) {
        if ((b == null) || (b.length == 0)) {
            return a;
        }
        T[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    protected void setCommonSystemProps() {
        System.setProperty("java.security.debug", "true");
        System.setProperty("NO_DEIMOS", "true");
        System.setProperty("NO_DEFAULT", "true");
        System.setProperty("CUSTOM_DB_DIR", TEST_CLASSES);
    }

    protected void copyNssCertKeyToClassesDir() throws IOException {
        Path dbPath = Path.of(BASE).getParent().resolve("nss").resolve("db");
        copyNssCertKeyToClassesDir(dbPath);
    }

    protected void copyNssCertKeyToClassesDir(Path dbPath) throws IOException {
        Path destinationPath = Path.of(TEST_CLASSES);
        String keyDbFile3 = "key3.db";
        String keyDbFile4 = "key4.db";
        String certDbFile8 = "cert8.db";
        String certDbFile9 = "cert9.db";

        Files.copy(dbPath.resolve(certDbFile8),
                destinationPath.resolve(certDbFile8),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(dbPath.resolve(certDbFile9),
                destinationPath.resolve(certDbFile9),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(dbPath.resolve(keyDbFile3),
                destinationPath.resolve(keyDbFile3),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(dbPath.resolve(keyDbFile4),
                destinationPath.resolve(keyDbFile4),
                StandardCopyOption.REPLACE_EXISTING);
    }

    // NSS version info
    public static enum ECCState {None, Basic, Extended}

    @Artifact(
            organization = NSSLIB,
            name = "nsslib-windows_x64",
            revision = NSS_BUNDLE_VERSION,
            extension = "zip")
    private static class WINDOWS_X64 {
    }

    @Artifact(
            organization = NSSLIB,
            name = "nsslib-macosx_x64",
            revision = NSS_BUNDLE_VERSION,
            extension = "zip")
    private static class MACOSX_X64 {
    }

    @Artifact(
            organization = NSSLIB,
            name = "nsslib-macosx_aarch64",
            revision = NSS_BUNDLE_VERSION,
            extension = "zip")
    private static class MACOSX_AARCH64 {
    }

    @Artifact(
            organization = NSSLIB,
            name = "nsslib-linux_x64",
            revision = NSS_BUNDLE_VERSION,
            extension = "zip")
    private static class LINUX_X64 {
    }

    @Artifact(
            organization = NSSLIB,
            name = "nsslib-linux_aarch64",
            revision = NSS_BUNDLE_VERSION,
            extension = "zip")
    private static class LINUX_AARCH64{
    }
}
