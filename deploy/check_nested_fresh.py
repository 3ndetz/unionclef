"""Refuse to deploy a jar whose NESTED module jars are not the freshly built ones.

WHY THIS EXISTS. `gradlew :1.21.11:build` packages shredder and tungsten as nested jars under
META-INF/jars/. On 2026-08-05 :shredder:compileJava FAILED on an unreported checked exception and
the version build went on packaging the last SUCCESSFUL shredder jar -- eleven hours old. So a fix
to BlockOptionalMeta.holder(), replacing an unbounded CompletableFuture.join() that was freezing the
client tick, was written, committed, deployed and MEASURED AS FAILED, because the deployed bytecode
still called join(). javap on the shipped class proved it, and the commit message had recorded a
false refutation of a fix that never ran.

That is the inner twin of the guard deploy_jar.sh already has for the OUTER jar (two jars claiming
one mod id, 2026-07-27).

HOW IT CHECKS. Not by the nested entry's timestamp -- Loom normalises those to 1980/1970, which is
why the first version of this script called every build stale. Two honest signals instead:

  1. the module's OWN jar on disk is newer than the newest .java under that module
     -> catches a failed compile leaving yesterday's jar behind;
  2. the nested copy is byte-identical to that jar
     -> catches the version build packaging some other, older copy.

Exit 0 = fresh, exit 1 = stale (with the offending module named).
"""
import io, os, sys, zipfile, hashlib, datetime

# --bless records the CURRENT source hash as the one the jar on disk was built from.
# It exists for one narrow case: the jar has been verified by other means (javap on the shipped
# class) while the sources carry a newer mtime with identical bytes, which no rebuild can clear
# because gradle compares content. Use it after verifying, never instead of verifying.
BLESS = "--bless" in sys.argv
argv = [a for a in sys.argv[1:] if not a.startswith("--")]
jar = argv[0]
root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
MODULES = {"shredder": "shredder/build/libs/shredder-0.1.0.jar",
           "tungsten": "tungsten/build/libs/tungsten-BETA-1.0.0.jar"}

def newest_source(d):
    newest, where = 0.0, ""
    for base, _, files in os.walk(os.path.join(root, d, "src")):
        for f in files:
            if f.endswith(".java"):
                p = os.path.join(base, f)
                t = os.path.getmtime(p)
                if t > newest:
                    newest, where = t, p
    return newest, where

def source_hash(d):
    """One hash over every .java in the module, path-ordered, so it is stable across machines."""
    h = hashlib.sha256()
    for base, dirs, files in os.walk(os.path.join(root, d, "src")):
        dirs.sort()
        for f in sorted(files):
            if f.endswith(".java"):
                p = os.path.join(base, f)
                h.update(os.path.relpath(p, root).replace(os.sep, "/").encode())
                with open(p, "rb") as fh:
                    h.update(fh.read())
    return h.hexdigest()


def stamp(t):
    return datetime.datetime.fromtimestamp(t).strftime("%m-%d %H:%M")

if BLESS:
    for name, rel in MODULES.items():
        io.open(os.path.join(root, rel) + ".srchash", "w").write(source_hash(name))
        print("blessed %s at %s" % (name, source_hash(name)[:12]))
    sys.exit(0)

bad = []
with zipfile.ZipFile(jar) as z:
    nested = [n for n in z.namelist() if n.startswith("META-INF/jars/") and n.endswith(".jar")]
    for name, rel in MODULES.items():
        built = os.path.join(root, rel)
        if not os.path.exists(built):
            bad.append("%s: %s does not exist -- the module was never built" % (name, rel))
            continue
        src_t, src_p = newest_source(name)
        jar_t = os.path.getmtime(built)
        if src_t > jar_t + 60:  # a minute of slack for clock/packaging skew
            # A NEWER MTIME IS NOT A CHANGE, AND GRADLE KNOWS IT.
            # Gradle decides by CONTENT, so a file whose mtime moved but whose bytes did not is
            # never recompiled -- and a pure mtime check then stays red forever with no rebuild
            # able to clear it. That happened the first time this guard ran for real: testing that
            # it CAN fail (by touching a source) left it refusing every deploy afterwards.
            # So ask the same question gradle asks. The hash of every module source is recorded
            # beside the jar whenever the guard passes; if the sources are newer but hash the
            # same, nothing changed and the jar is still the right one.
            h = source_hash(name)
            rec = built + ".srchash"
            if os.path.exists(rec) and io.open(rec).read().strip() == h:
                print("  %s: sources touched but unchanged (hash %s) -- jar is still current"
                      % (name, h[:12]))
            else:
                bad.append("%s: %s built %s but %s changed %s -- the compile did not run or FAILED"
                           % (name, os.path.basename(rel), stamp(jar_t),
                              os.path.relpath(src_p, root), stamp(src_t)))
                continue
        ent = [n for n in nested if os.path.basename(n) == os.path.basename(rel)]
        if not ent:
            bad.append("%s: not packaged inside %s" % (name, os.path.basename(jar)))
            continue
        inner = hashlib.sha256(z.read(ent[0])).hexdigest()
        with open(built, "rb") as fh:
            outer = hashlib.sha256(fh.read()).hexdigest()
        if inner != outer:
            bad.append("%s: the copy inside the mod jar is NOT the jar just built (%s vs %s)" % (
                name, inner[:12], outer[:12]))

if bad:
    print("STALE NESTED JAR -- refusing to deploy a build that would measure the wrong code:")
    for b in bad:
        print("  " + b)
    print("Fix: gradlew :shredder:remapJar :tungsten:remapJar :1.21.11:build")
    sys.exit(1)
# RECORD WHAT WAS BLESSED, so a later mtime-only bump can be recognised as harmless.
for name, rel in MODULES.items():
    try:
        io.open(os.path.join(root, rel) + ".srchash", "w").write(source_hash(name))
    except Exception:
        pass
print("nested module jars are fresh and byte-identical to the built ones")
