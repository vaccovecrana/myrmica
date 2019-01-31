package io.vacco.myrmica.maven;

import org.joox.Match;
import org.slf4j.*;

import java.io.File;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import static java.util.Objects.*;
import static org.joox.JOOX.*;
import static io.vacco.myrmica.maven.PropertyAccess.*;
import static io.vacco.myrmica.maven.Constants.*;

public class Repository {

  private static final Logger log = LoggerFactory.getLogger(Repository.class);

  private Path localRoot;
  private URI remoteRoot;
  private final Map<Coordinates, Pom> resolvedPoms = new TreeMap<>();

  public Repository(String localRootPath, String remotePath) {
    this.localRoot = Paths.get(requireNonNull(localRootPath));
    if (!localRoot.toFile().exists()) {
      throw new IllegalArgumentException(String.format("Missing root folder: [%s]", localRoot.toAbsolutePath().toString()));
    }
    if (!localRoot.toFile().isDirectory()) {
      throw new IllegalArgumentException(String.format("Not a directory: [%s]", localRoot.toAbsolutePath().toString()));
    }
    if (!requireNonNull(remotePath).endsWith("/")) {
      throw new IllegalArgumentException(String.format("Remote path does not end with a trailing slash: [%s]", remotePath));
    }
    try { this.remoteRoot = new URI(remotePath); }
    catch (URISyntaxException e) { throw new IllegalStateException(e); }
  }

  private Match loadPom(Coordinates c) {
    requireNonNull(c);
    try {
      Path target = c.getLocalPomPath(localRoot);
      if (!target.toFile().getParentFile().exists()) { target.toFile().getParentFile().mkdirs(); }
      if (!target.toFile().exists()) {
        URI remotePom = c.getPomUri(remoteRoot);
        log.info("Fetching [{}]", remotePom);
        Files.copy(remotePom.toURL().openStream(), target);
      }
      return $(target.toFile());
    }
    catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Optional<Coordinates> loadParent(Match pom) {
    Match p = pom.child(PomTag.parent.toString());
    if (p.size() == 0) return Optional.empty();
    return Optional.of(new Coordinates(p));
  }

  public Pom buildPom(Coordinates c) {
    return resolvedPoms.computeIfAbsent(c, coordinates -> {
      List<Match> poms = new ArrayList<>();
      Optional<Coordinates> oc = Optional.of(coordinates);
      while (oc.isPresent()) {
        Match pp = loadPom(oc.get());
        poms.add(pp);
        oc = loadParent(pp);
      }

      String rootPackaging = poms.get(0).child(ComponentTag.packaging.toString()).text();
      Optional<Coordinates> parentCoords = loadParent(poms.get(0));
      Optional<Match> ePom = poms.stream()
          .map(pom -> NodeUtil.filterTop(pom, PomTag.exclusionTags()))
          .reduce((pom0, pom1) ->
              NodeUtil.merge(pom1, pom0));

      if (rootPackaging != null) {
        ePom.get().child(ComponentTag.packaging.toString()).text(rootPackaging);
      }

      Map<String, String> rawProps = loadProperties(ePom.get());
      rawProps.put("project.build.directory", new File(".").getAbsolutePath());
      rawProps.put("project.groupId", coordinates.getGroupId());
      rawProps.put("project.artifactId", coordinates.getArtifactId());
      rawProps.put("project.version", coordinates.getVersion());
      if (parentCoords.isPresent()) {
        rawProps.put("project.parent.groupId", parentCoords.get().getGroupId());
        rawProps.put("project.parent.artifactId", parentCoords.get().getArtifactId());
        rawProps.put("project.parent.version", parentCoords.get().getVersion());
      }

      resolvePomKeyReferences(ePom.get(), resolveProperties(rawProps));
      return new Pom(ePom.get());
    });
  }

  private void loadRtTail(DependencyNode context, Set<Artifact> resolved) {
    if (context.artifact.isRuntime()) { resolved.add(context.artifact); }
    for (Artifact rd : context.pom.getDependencies(true)) {
      if (context.excludes(rd)) return;
      if (context.isTopLevelOverride(rd)) return;
      if (rd.getMetadata().classifier != null) { resolved.add(rd); }
      if (!resolved.contains(rd)) {
        loadRtTail(new DependencyNode(buildPom(rd.getAt()), rd, context), resolved);
      }
    }
  }

  public Set<Artifact> loadRuntimeArtifactsAt(Coordinates root) {
    Set<Artifact> result = new TreeSet<>();
    DependencyNode n0 = new DependencyNode(buildPom(root));
    loadRtTail(n0, result);
    return result;
  }
}
