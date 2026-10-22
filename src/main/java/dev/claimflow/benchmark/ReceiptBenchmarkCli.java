package dev.claimflow.benchmark;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dependency-free command line for the receipt benchmark contract. After {@code ./mvnw compile}:
 *
 * <pre>
 * java -cp target/classes dev.claimflow.benchmark.ReceiptBenchmarkCli validate MANIFEST.csv [--images DIR]
 * java -cp target/classes dev.claimflow.benchmark.ReceiptBenchmarkCli score MANIFEST.csv PREDICTIONS.csv --split development
 * </pre>
 */
public final class ReceiptBenchmarkCli {
    private ReceiptBenchmarkCli() {}

    public static void main(String[] args) {
        System.exit(run(args, LocalDate.now(), System.out, System.err));
    }

    /** Exit codes: 0 success, 1 invalid manifest or predictions, 2 usage error. */
    static int run(String[] args, LocalDate today, PrintStream out, PrintStream err) {
        if (args.length < 2 || !(args[0].equals("validate") || args[0].equals("score"))) {
            err.println("usage: validate MANIFEST.csv [--images DIR] | score MANIFEST.csv PREDICTIONS.csv --split SPLIT");
            return 2;
        }
        try {
            ReceiptManifest manifest = ReceiptManifest.parse(read(args[1]));
            if (args[0].equals("validate")) {
                Path images = option(args, "--images") == null ? null : Path.of(option(args, "--images"));
                return report(manifest, ManifestValidator.validate(manifest, today, images), out, err);
            }
            String split = option(args, "--split");
            if (args.length < 3 || split == null) {
                err.println("score needs PREDICTIONS.csv and --split train|development|test");
                return 2;
            }
            List<String> errors = ManifestValidator.validate(manifest, today, null);
            if (!errors.isEmpty()) return report(manifest, errors, out, err);
            ExtractionScorer.Report result = ExtractionScorer.score(manifest, Prediction.parse(read(args[2])), split);
            if (result.syntheticReceipts() > 0) {
                err.println("WARNING: " + result.syntheticReceipts()
                    + " synthetic fixture receipt(s); this is a harness check, not an OCR accuracy result.");
            }
            out.print(result.toJson());
            return 0;
        } catch (IOException | IllegalArgumentException invalid) {
            err.println("error: " + invalid.getMessage());
            return 1;
        }
    }

    private static int report(ReceiptManifest manifest, List<String> errors, PrintStream out, PrintStream err) {
        if (!errors.isEmpty()) {
            errors.forEach(err::println);
            err.println(errors.size() + " manifest problem(s)");
            return 1;
        }
        Map<String, Integer> counts = new TreeMap<>();
        manifest.entries().forEach(e -> counts.merge(e.split(), 1, Integer::sum));
        out.println("OK: " + manifest.entries().size() + " receipts " + counts);
        return 0;
    }

    private static String option(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return null;
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
