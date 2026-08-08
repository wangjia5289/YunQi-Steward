import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Source-file-mode verifier for the exact BOM-managed release artifact set. */
public final class VerifyStagedRelease {

    private VerifyStagedRelease() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("expected project and staging directories");
        }
        Path project = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path repository = Path.of(arguments[1]).toAbsolutePath().normalize()
                .resolve("yunqi/zhibei");
        Set<String> artifacts = bomArtifacts(project.resolve("bom/pom.xml"));
        if (artifacts.size() != 35) {
            throw new IllegalStateException("expected 35 BOM artifacts, found " + artifacts.size());
        }
        for (String artifact : artifacts) {
            requireArtifact(repository, artifact);
        }
        requireFile(repository.resolve(
                "steward-parent/0.1.0/steward-parent-0.1.0.pom"));
        requireFile(repository.resolve(
                "steward-bom/0.1.0/steward-bom-0.1.0.pom"));
        requireAbsent(repository.resolve("steward-example-observation-e2e"));
        requireAbsent(repository.resolve("steward-benchmark-observation"));
        System.out.println("Exact BOM artifact set verified: 35 published JAR modules");
    }

    private static Set<String> bomArtifacts(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(pom.toFile());
        var dependencies = document.getElementsByTagNameNS("*", "dependency");
        Set<String> artifacts = new HashSet<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String artifact = dependency.getElementsByTagNameNS("*", "artifactId")
                    .item(0).getTextContent().strip();
            if (!artifacts.add(artifact)) {
                throw new IllegalStateException("duplicate BOM artifact: " + artifact);
            }
        }
        return Set.copyOf(artifacts);
    }

    private static void requireArtifact(Path repository, String artifact) throws IOException {
        Path version = repository.resolve(artifact).resolve("0.1.0");
        requireFile(version.resolve(artifact + "-0.1.0.pom"));
        requireFile(version.resolve(artifact + "-0.1.0.jar"));
        requireFile(version.resolve(artifact + "-0.1.0-sources.jar"));
        requireFile(version.resolve(artifact + "-0.1.0-javadoc.jar"));
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            throw new IllegalStateException("missing or empty staged artifact: " + file);
        }
    }

    private static void requireAbsent(Path path) {
        if (Files.exists(path)) {
            throw new IllegalStateException("non-published module was staged: " + path);
        }
    }
}
