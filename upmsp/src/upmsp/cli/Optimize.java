package upmsp.cli;

import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Command;
import upmsp.algorithm.constructive.SimpleConstructive;
import upmsp.algorithm.heuristic.AdaptiveSA;
import upmsp.algorithm.heuristic.Heuristic;
import upmsp.algorithm.heuristic.SA;
import upmsp.algorithm.neighborhood.*;
import upmsp.algorithm.utility.StandardUtilityModel;
import upmsp.model.Problem;
import upmsp.model.solution.Solution;
import upmsp.util.Util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Command to solve an instance of the UPMSP.
 *
 * @author Andre L. Maravilha
 */
@Command(description = "Solve an instance of the UPMS problem.",
        name = "optimize", mixinStandardHelpOptions = true)
public class Optimize implements Callable<Void> {

    @Option(names = {"--verbose"}, description = "Show optimization progress.")
    private boolean verbose = false;

    @Option(names = {"--algorithm"}, description = "sa, adaptive-sa", defaultValue = "sa")
    private String algorithm;

    @Option(names = {"--seed"}, description = "Seed for pseudo-random number generator.", defaultValue = "0")
    private long seed;

    @Option(names = {"--initial-temperature"}, description = "Initial temperature for the Simulated Annealing.", defaultValue = "1.0")
    private double initialTemperature;

    @Option(names = {"--cooling-rate"}, description = "Cooling rate", defaultValue = "0.96")
    private double coolingRate;

    @Option(names = {"--iterations-per-temperature"}, description = "Iterations before update temperature", defaultValue = "1176628")
    private int iterationsPerTemperature;

    @Option(names = {"--coefficients-file"}, description = "Path of the coefficients of the utility model.")
    private File utilityCoefficientsFile;

    @Option(names = {"--update-frequency"}, description = "Iterations before update utility values.", defaultValue = "1")
    private long updateFrequency;

    @Option(names = {"--max-probability"}, description = "Maximum probability of choosing a move.", defaultValue = "1")
    private long maxProbability;

    @Option(names = {"--time-limit"}, description = "Maximum runtime (in milliseconds). If negative, it is set according to the problem size.", defaultValue = "-1")
    private long timeLimit;

    @Option(names = {"--iterations-limit"}, description = "Maximum number of iterations the algorithm can perform.")
    private long iterationsLimit = Long.MAX_VALUE;

    @Option(names = {"--disable"}, description = "shift, direct-swap, swap, switch, task-move, two-shift")
    private String[] disabledMoves = new String[0];

    @Option(names = {"--track"}, description = "Path to the (optional) output file in which makespan of incumbent solutions are tracked.")
    private File trackFile;

    @Parameters(index = "0", description = "Path of the problem input file.", arity = "1..1")
    private File input;

    @Parameters(index = "1", description = "Path of the (output) solution file.", arity = "0..1")
    private File output;

    @Override
    public Void call() throws Exception {

        // Instantiate a random number generator
        Random random = new Random(seed);

        // Load problem data from file
        Problem problem = new Problem(input.getAbsolutePath());

        // Instantiate the chosen heuristic
        Heuristic heuristic = null;
        switch (algorithm.toLowerCase()) {

            case "sa":
                heuristic = new SA(problem, random, coolingRate, initialTemperature, iterationsPerTemperature);
                break;

            case "adaptive-sa":
                try {
                    heuristic = new AdaptiveSA(problem, random, coolingRate, initialTemperature, iterationsPerTemperature,
                            new StandardUtilityModel(utilityCoefficientsFile.toPath().toAbsolutePath()), updateFrequency, maxProbability);
                } catch (IOException e) {
                    System.err.println("ERROR: Could not read file of coefficients of the utility model.");
                    System.exit(-1);
                    return null;
                }
                break;

            default:
                System.err.println("ERROR: Invalid algorithm.");
                System.exit(-1);
                return null;
        }

        // Add moves (neighborhoods)
        List<String> enabledMoves = new ArrayList<>(Arrays.asList("shift", "direct-swap", "swap", "switch", "task-move", "two-shift"));
        enabledMoves.removeAll(Arrays.asList(disabledMoves));

        for (String move : enabledMoves) {
            switch (move) {

                case "shift":
                    heuristic.addMove(new Shift(problem, random, 1, true));
                    heuristic.addMove(new Shift(problem, random, 1, false));
                    heuristic.addMove(new ShiftSmart(problem, random, 1, true));
                    heuristic.addMove(new ShiftSmart(problem, random, 1, false));
                    break;

                case "direct-swap":
                    heuristic.addMove(new SimpleSwap(problem, random, 1, true));
                    heuristic.addMove(new SimpleSwap(problem, random, 1, false));
                    heuristic.addMove(new SimpleSwapSmart(problem, random, 1, true));
                    heuristic.addMove(new SimpleSwapSmart(problem, random, 1, false));
                    break;

                case "swap":
                    heuristic.addMove(new Swap(problem, random, 1, true));
                    heuristic.addMove(new Swap(problem, random, 1, false));
                    heuristic.addMove(new SwapSmart(problem, random, 1, true));
                    heuristic.addMove(new SwapSmart(problem, random, 1, false));
                    break;

                case "switch":
                    heuristic.addMove(new Switch(problem, random, 1, true));
                    heuristic.addMove(new Switch(problem, random, 1, false));
                    heuristic.addMove(new SwitchSmart(problem, random, 1, true));
                    heuristic.addMove(new SwitchSmart(problem, random, 1, false));
                    break;

                case "task-move":
                    heuristic.addMove(new TaskMove(problem, random, 1, true));
                    heuristic.addMove(new TaskMove(problem, random, 1, false));
                    heuristic.addMove(new TaskMoveSmart(problem, random, 1, true));
                    heuristic.addMove(new TaskMoveSmart(problem, random, 1, false));
                    break;

                case "two-shift":
                    heuristic.addMove(new TwoShift(problem, random, 1, true));
                    heuristic.addMove(new TwoShift(problem, random, 1, false));
                    heuristic.addMove(new TwoShiftSmart(problem, random, 1, true));
                    heuristic.addMove(new TwoShiftSmart(problem, random, 1, false));
                    break;
            }
        }

        // Time limit
        if (timeLimit < 0L) {
            timeLimit = (long) (problem.nJobs * (problem.nMachines / 2.0) * 30);
        }

        // Log (if verbose)
        if (verbose) {

            System.out.printf("\n");
            System.out.printf("Instance......: %s\n", input.getName());
            System.out.printf("Algorithm.....: %s\n", heuristic);
            System.out.printf("Other params..: seed=%d, iterations-limit=%s, time-limit=%.2fs\n\n", seed, Util.longToString(iterationsLimit), timeLimit / 1000.0);

            System.out.printf("+-------------------------------------------------------------+\n");
            System.out.printf("|                    Optimization progress                    |\n");
            System.out.printf("+--------------+---------------+---------------+--------------+\n");
            System.out.printf("|   Iteration  |   Incumbent   |    Current    |   Time (s)   |\n");
            System.out.printf("+--------------+---------------+---------------+--------------+\n");
        }

        // Create initial solution
        long initialSolutionRuntime = System.currentTimeMillis();
        Solution solution = SimpleConstructive.randomSolution(problem, random);
        initialSolutionRuntime = System.currentTimeMillis() - initialSolutionRuntime;

        // Subtract time spent with creation of an initial solution
        timeLimit = timeLimit - initialSolutionRuntime;

        // Callback, if enabled
        Callback callback = null;
        if (trackFile != null) {
            callback = new Callback(problem, input.toPath(), seed);
        }

        // Run heuristic
        long runtime = 0L;
        if (heuristic.getMoves().size() > 0) {
            runtime = System.currentTimeMillis();
            solution = heuristic.run(solution, timeLimit, iterationsLimit, callback, (verbose ? System.out : null));
            runtime = System.currentTimeMillis() - runtime;
        }

        // Export callback data, if set
        if (callback != null) {
            callback.exportToCSV(trackFile.toPath());
        }

        // Check feasibility
        ByteArrayOutputStream feasibilityInfo = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(feasibilityInfo, true, "UTF-8");
        boolean feasible = solution.validate((verbose ? stream : null));

        // Log (if verbose)
        if (verbose) {
            System.out.printf("+--------------+---------------+---------------+--------------+\n\n");

            // Neighborhood stats
            System.out.printf("+----------------------------------------------------------------------------------+\n");
            System.out.printf("|                             Neighborhoods statistics                             |\n");
            System.out.printf("+-----------------------+--------------+----------+----------+----------+----------+\n");
            System.out.printf("|          Move         |     Calls    | Improvs. | Sideways |  Accepts |  Rejects |\n");
            System.out.printf("+-----------------------+--------------+----------+----------+----------+----------+\n");

            for (Move move : heuristic.getMoves()) {
                Util.safePrintMoveStatistics(System.out, move, "");
            }

            System.out.printf("+-----------------------+--------------+----------+----------+----------+----------+\n\n");

            // Feasibility info
            if (feasible) {
                System.out.printf("Feasible solution found!\n\n");
            } else {
                System.out.printf("Solution is infeasible:\n%s\n",
                        new String(feasibilityInfo.toByteArray(), StandardCharsets.UTF_8));
            }

            // General info
            System.out.printf("Best makespan......: %d\n", solution.getCost());
            System.out.printf("N. of iterations...: %d\n", heuristic.getNIters());
            System.out.printf("Total runtime (s)..: %.4fs\n\n", (initialSolutionRuntime + runtime) / 1000.0);
        }

        // Output for non verbose mode
        if (!verbose) {
            System.out.printf("%d %d %d\n",
                    solution.getCost(),
                    heuristic.getNIters(),
                    initialSolutionRuntime + runtime);
        }

        // Save best solution found
        if (output != null) {
            solution.write(Paths.get(output.getAbsolutePath()));
        }

        return null;
    }


    /**
     * Callback class to track the progress of the optimization process.
     */
    private static class Callback implements Heuristic.Callback {

        /**
         * Keep an entry of the track.
         */
        private static class Entry {

            public long makespan, timeMillis, iteration;
            public double timePerc;

            public Entry(long makespan, double timePerc, long timeMillis, long iteration) {
                this.makespan = makespan;
                this.timePerc = timePerc;
                this.timeMillis = timeMillis;
                this.iteration = iteration;
            }
        }

        private String instance;
        private long n, m, seed;
        private List<Entry> entries;

        /**
         * Constructor.
         * @param problem Reference to the problem.
         * @param instance Path to the instance file.
         * @param seed Seed used by the heuristic.
         */
        public Callback(Problem problem, Path instance, long seed) {
            this.entries = new LinkedList<>();
            this.instance = instance.getFileName().toString().replace(".txt", "");
            this.seed = seed;
            this.n = problem.nJobs;
            this.m = problem.nMachines;
        }

        @Override
        public void onNewIncumbent(Solution incumbent, Class<? extends Move> move, long runtimeMillis, long timeLimitMillis, long iteration, long iterationLimit) {
            this.entries.add(new Entry(incumbent.getCost(), runtimeMillis / (double) timeLimitMillis, runtimeMillis, iteration));
        }

        @Override
        public void onIteration(Solution incumbent, long runtimeMillis, long timeLimitMillis, long iteration, long iterationLimit) {
            // Do nothing.
        }

        /**
         * Export data to CSV file.
         * @param output Path to file in which data should be written.
         * @throws IOException
         */
        public void exportToCSV(Path output) throws IOException {

            // Creates the directory hierarchy, if necessary
            output = output.toAbsolutePath();
            Files.createDirectories(output.getParent());

            // Write data to file
            try (BufferedWriter buffer = Files.newBufferedWriter(output); PrintWriter writer = new PrintWriter(buffer)) {
                writer.printf("INSTANCE,N,M,SEED,ITERATION,TIME.MILLIS,TIME.PERC,MAKESPAN\n");
                for (Entry entry : entries) {
                    writer.printf("%s,%d,%d,%d,%d,%d,%.6f,%d\n",
                            instance,
                            n,
                            m,
                            seed,
                            entry.iteration,
                            entry.timeMillis,
                            entry.timePerc,
                            entry.makespan);
                }
            }
        }
    }

}
