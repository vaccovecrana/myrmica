package io.vacco.myrmica.util;

import io.vacco.myrmica.maven.Artifact;
import io.vacco.myrmica.maven.Coordinates;
import org.joox.Match;
import java.util.Optional;
import static io.vacco.myrmica.maven.Constants.*;

public class NodeUtil {

  private static String idOf(Match n) {
    String tn = n.tag();
    if (tn == null) return null;
    if (tn.equals(PomTag.dependency.toString())) {
      Artifact a = Artifact.fromXml(n);
      return a.toExternalForm();
    } else if (tn.equals(PomTag.profile.toString())) {
      return n.child(PomTag.id.toString()).text();
    } else if (tn.equals(PomTag.exclusion.toString())) {
      Coordinates c = new Coordinates(n);
      return c.toExternalForm();
    }
    return n.tag();
  }

  private static Optional<Match> childWithId(Match n, String id) {
    return n.children().each().stream().filter(c -> {
      String cid = idOf(c);
      return cid != null && cid.equals(id);
    }).findFirst();
  }

  private static Match mergeTail(Match l, Match r) {
    for (Match rc : r.children().each()) {
      String key = idOf(rc);
      Optional<Match> lc = childWithId(l, key);
      if (!lc.isPresent()) {
        l.append(rc);
      }
      else if (lc.get().xpath("./*").size() > 0) {
        mergeTail(lc.get(), rc);
      } else {
        lc.get().remove();
        l.append(rc);
      }
    }
    return l;
  }

  public static Match merge(Match l, Match r) {
    return mergeTail(l, r);
  }

  public static Match filterTop(Match n, String ... tagExclusions) {
    for (String tagExclusion : tagExclusions) {
      n.child(tagExclusion).remove();
    }
    return n;
  }
}
