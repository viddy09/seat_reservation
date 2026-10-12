package com.paytmmoney.seatreservation.burst;

/**
 * Entry point: {@code java -jar burst.jar <BASE_URL> [options]}. Exit 0 when every check passes, 1 when any fails, 2 on
 * usage or setup errors.
 */
public final class BurstMain {

    static final int EXIT_PASS = 0;
    static final int EXIT_FAIL = 1;
    static final int EXIT_USAGE = 2;

    private BurstMain() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        BurstConfig config;
        try {
            config = BurstConfig.parse(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.println(BurstConfig.USAGE);
            return EXIT_USAGE;
        }
        System.out.printf(
                "Burst against %s: total=%d concurrency=%d seats=%d hot-seats=%d storm-size=%d users=%d%n",
                config.baseUrl(),
                config.total(),
                config.concurrency(),
                config.seats(),
                config.hotSeats(),
                config.stormSize(),
                config.users());
        try {
            BurstRun.Outcome outcome = new BurstRun(config, new Api(config.baseUrl(), config.concurrency())).execute();
            Report report = new Report(outcome);
            report.print(System.out);
            return report.passed() ? EXIT_PASS : EXIT_FAIL;
        } catch (IllegalStateException e) {
            StringBuilder message = new StringBuilder("Setup failed: ").append(e.getMessage());
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                message.append("\n  caused by: ").append(cause);
            }
            System.err.println(message);
            return EXIT_USAGE;
        }
    }
}
