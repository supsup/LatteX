import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compares the declared public/protected surface of packages exported by two
 * JPMS module descriptors. The descriptor chooses packages; compiled class
 * visibility is the only source of truth for the surface itself.
 */
public final class ExportedSurfaceGate {
    private static final Pattern MODULE_DECLARATION = Pattern.compile(
            "\\b(?:open\\s+)?module\\s+[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\s*\\{");
    private static final Pattern EXPORT = Pattern.compile(
            "\\bexports\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)"
                    + "(?:\\s+to\\s+[^;]+)?\\s*;");

    private ExportedSurfaceGate() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, Path> options = parseArgs(args);
        Set<String> base = surface(options.get("--base-classes"), options.get("--base-module-info"));
        Set<String> tip = surface(options.get("--tip-classes"), options.get("--tip-module-info"));
        Set<String> baseAllowlist = readAllowlist(options.get("--base-allowlist"));
        Set<String> tipAllowlist = readAllowlist(options.get("--tip-allowlist"));
        TreeSet<String> newIntentions = new TreeSet<>(tipAllowlist);
        newIntentions.removeAll(baseAllowlist);

        TreeSet<String> additions = new TreeSet<>(tip);
        additions.removeAll(base);
        TreeSet<String> removals = new TreeSet<>(base);
        removals.removeAll(tip);
        TreeSet<String> unapproved = new TreeSet<>(additions);
        unapproved.removeAll(newIntentions);
        TreeSet<String> unusedIntentions = new TreeSet<>(newIntentions);
        unusedIntentions.removeAll(additions);

        System.out.printf("Exported surface: base=%d tip=%d additions=%d removals=%d%n",
                base.size(), tip.size(), additions.size(), removals.size());
        printChanges("Added", additions, newIntentions);
        printChanges("Removed", removals, Set.of());

        if (!unapproved.isEmpty()) {
            System.err.println("ERROR: unapproved additions to an exported package:");
            unapproved.forEach(entry -> System.err.println("  + " + entry));
            System.err.println("Declare each intentional addition as an exact line in "
                    + options.get("--tip-allowlist") + ".");
        }
        if (!unusedIntentions.isEmpty()) {
            System.err.println("ERROR: new intentional-additions lines without matching surface additions:");
            unusedIntentions.forEach(entry -> System.err.println("  ! " + entry));
            System.err.println("Remove stale, misspelled, or pre-emptive declarations from "
                    + options.get("--tip-allowlist") + ".");
        }
        if (!unapproved.isEmpty() || !unusedIntentions.isEmpty()) {
            System.exit(1);
        }

        System.out.println(additions.isEmpty()
                ? "PASS: no exported-surface additions."
                : "PASS: every exported-surface addition is explicitly intentional.");
    }

    private static Map<String, Path> parseArgs(String[] args) {
        Set<String> required = Set.of(
                "--base-classes", "--base-module-info",
                "--tip-classes", "--tip-module-info",
                "--base-allowlist", "--tip-allowlist");
        if (args.length != required.size() * 2) {
            usage();
        }
        Map<String, Path> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!required.contains(args[i]) || options.put(args[i], Path.of(args[i + 1])) != null) {
                usage();
            }
        }
        if (!options.keySet().equals(required)) {
            usage();
        }
        options.forEach((flag, path) -> {
            if (!Files.exists(path)) {
                throw new IllegalArgumentException(flag + " does not exist: " + path);
            }
        });
        return options;
    }

    private static void usage() {
        System.err.println("Usage: java ExportedSurfaceGate.java "
                + "--base-classes DIR --base-module-info FILE "
                + "--tip-classes DIR --tip-module-info FILE "
                + "--base-allowlist FILE --tip-allowlist FILE");
        System.exit(2);
    }

    private static Set<String> surface(Path classes, Path moduleInfo) throws IOException {
        Set<String> exportedPackages = exportedPackages(moduleInfo);
        Map<String, ParsedClass> parsed = new HashMap<>();
        try (Stream<Path> paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".class"))
                    .sorted()
                    .toList()) {
                ClassModel model = ClassFile.of().parse(path);
                if (model.isModuleInfo()) {
                    continue;
                }
                String internalName = model.thisClass().asInternalName();
                if (internalName.endsWith("/package-info") || internalName.equals("package-info")) {
                    continue;
                }
                parsed.put(internalName, new ParsedClass(model, selfInnerInfo(model)));
            }
        }

        TreeSet<String> result = new TreeSet<>();
        for (Map.Entry<String, ParsedClass> entry : parsed.entrySet()) {
            String internalName = entry.getKey();
            if (!exportedPackages.contains(packageName(internalName))
                    || !isAccessibleType(internalName, parsed, new HashSet<>())) {
                continue;
            }
            ClassModel model = entry.getValue().model();
            String owner = binaryName(internalName);
            result.add(typeVisibility(entry.getValue()) + " type " + owner);

            for (FieldModel field : model.fields()) {
                String visibility = memberVisibility(field.flags().flags());
                if (visibility == null || field.flags().has(AccessFlag.SYNTHETIC)) {
                    continue;
                }
                result.add(visibility + staticMarker(field.flags().flags())
                        + "field " + owner + "#" + field.fieldName().stringValue()
                        + ":" + typeName(field.fieldTypeSymbol()));
            }
            for (MethodModel method : model.methods()) {
                String visibility = memberVisibility(method.flags().flags());
                String name = method.methodName().stringValue();
                if (visibility == null || name.equals("<clinit>")
                        || method.flags().has(AccessFlag.SYNTHETIC)
                        || method.flags().has(AccessFlag.BRIDGE)) {
                    continue;
                }
                MethodTypeDesc type = method.methodTypeSymbol();
                String parameters = type.parameterList().stream()
                        .map(ExportedSurfaceGate::typeName)
                        .reduce((left, right) -> left + "," + right)
                        .orElse("");
                if (name.equals("<init>")) {
                    result.add(visibility + " constructor " + owner + "(" + parameters + ")");
                } else {
                    result.add(visibility + staticMarker(method.flags().flags())
                            + "method " + owner + "#" + name + "(" + parameters + ")"
                            + ":" + typeName(type.returnType()));
                }
            }
        }
        return result;
    }

    private static Set<String> exportedPackages(Path moduleInfo) throws IOException {
        String source = Files.readString(moduleInfo, StandardCharsets.UTF_8);
        String withoutComments = source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
        if (!MODULE_DECLARATION.matcher(withoutComments).find()) {
            throw new IllegalArgumentException("No JPMS module declaration found in " + moduleInfo);
        }
        TreeSet<String> packages = new TreeSet<>();
        Matcher matcher = EXPORT.matcher(withoutComments);
        while (matcher.find()) {
            packages.add(matcher.group(1));
        }
        return packages;
    }

    private static Optional<InnerClassInfo> selfInnerInfo(ClassModel model) {
        String self = model.thisClass().asInternalName();
        return model.findAttribute(Attributes.innerClasses())
                .flatMap(attribute -> attribute.classes().stream()
                        .filter(info -> info.innerClass().asInternalName().equals(self))
                        .findFirst());
    }

    private static boolean isAccessibleType(
            String internalName,
            Map<String, ParsedClass> parsed,
            Set<String> visiting) {
        ParsedClass candidate = parsed.get(internalName);
        if (candidate == null || candidate.model().flags().has(AccessFlag.SYNTHETIC)
                || !visiting.add(internalName)) {
            return false;
        }
        try {
            Optional<InnerClassInfo> inner = candidate.innerInfo()
                    .filter(info -> info.outerClass().isPresent());
            if (inner.isEmpty()) {
                return candidate.model().flags().has(AccessFlag.PUBLIC)
                        || candidate.model().flags().has(AccessFlag.PROTECTED);
            }
            InnerClassInfo info = inner.get();
            if (info.innerName().isEmpty()
                    || !(info.has(AccessFlag.PUBLIC) || info.has(AccessFlag.PROTECTED))) {
                return false;
            }
            return isAccessibleType(info.outerClass().orElseThrow().asInternalName(), parsed, visiting);
        } finally {
            visiting.remove(internalName);
        }
    }

    private static String typeVisibility(ParsedClass parsed) {
        Optional<InnerClassInfo> inner = parsed.innerInfo()
                .filter(info -> info.outerClass().isPresent());
        if (inner.isPresent() && inner.get().has(AccessFlag.PROTECTED)) {
            return "protected";
        }
        return "public";
    }

    private static String memberVisibility(Set<AccessFlag> flags) {
        if (flags.contains(AccessFlag.PUBLIC)) {
            return "public";
        }
        if (flags.contains(AccessFlag.PROTECTED)) {
            return "protected";
        }
        return null;
    }

    private static String staticMarker(Set<AccessFlag> flags) {
        return flags.contains(AccessFlag.STATIC) ? " static " : " ";
    }

    private static String packageName(String internalName) {
        int separator = internalName.lastIndexOf('/');
        return separator < 0 ? "" : internalName.substring(0, separator).replace('/', '.');
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String typeName(ClassDesc type) {
        String descriptor = type.descriptorString();
        int dimensions = 0;
        while (descriptor.charAt(dimensions) == '[') {
            dimensions++;
        }
        String component = switch (descriptor.charAt(dimensions)) {
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'Z' -> "boolean";
            case 'V' -> "void";
            case 'L' -> descriptor.substring(dimensions + 1, descriptor.length() - 1)
                    .replace('/', '.');
            default -> throw new IllegalArgumentException("Unsupported descriptor: " + descriptor);
        };
        return component + "[]".repeat(dimensions);
    }

    private static Set<String> readAllowlist(Path path) throws IOException {
        TreeSet<String> entries = new TreeSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String candidate = line.strip();
            if (!candidate.isEmpty() && !candidate.startsWith("#")) {
                entries.add(candidate);
            }
        }
        return entries;
    }

    private static void printChanges(String label, Set<String> changes, Set<String> allowlist) {
        if (changes.isEmpty()) {
            return;
        }
        System.out.println(label + " (" + changes.size() + "):");
        for (String change : changes) {
            String suffix = allowlist.contains(change) ? " [intentional]" : "";
            System.out.println("  " + (label.equals("Added") ? "+ " : "- ") + change + suffix);
        }
    }

    private record ParsedClass(ClassModel model, Optional<InnerClassInfo> innerInfo) {
    }
}
