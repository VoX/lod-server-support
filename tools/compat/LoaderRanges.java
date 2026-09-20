import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

/** Test/tool-only bridge to real loader range implementations; never shipped. */
class LoaderRanges {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            String[] row = line.split("\t", -1);
            try {
                if (row.length != 3) throw new IllegalArgumentException();
                String version = new String(Base64.getDecoder().decode(row[1]), StandardCharsets.UTF_8);
                String range = new String(Base64.getDecoder().decode(row[2]), StandardCharsets.UTF_8);
                boolean accepted;
                if (row[0].equals("fabric")) {
                    accepted = VersionPredicate.parse(range).test(Version.parse(version));
                } else if (row[0].equals("maven")) {
                    accepted = range.equals("*") || VersionRange.createFromVersionSpec(range)
                            .containsVersion(new DefaultArtifactVersion(version));
                } else throw new IllegalArgumentException();
                System.out.println(accepted ? "accepted" : "rejected");
            } catch (Exception | LinkageError e) {
                System.out.println("unsupported");
            }
        }
    }
}
