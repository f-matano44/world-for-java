import jp.f_matano44.ja_world.CheapTrick;
import jp.f_matano44.ja_world.D4C;
import jp.f_matano44.ja_world.Dio;
import jp.f_matano44.ja_world.StoneMask;
import jp.f_matano44.ja_world.Synthesis;
import jp.f_matano44.jfloatwavio.WavIO;


/**
 * Test program.
 * $ ja-world/$ java -jar bin/TestApp.jar input.wav output.wav
 * Input
 *      input.wav  : argv[0] Input file
 * Output
 *      output.wav : argv[1] Output file
 */
public class TestApp {
    /** Test code. */
    public static void main(String[] args) {
        final String inputFilename = args[0];
        final String outputFilename = args[1];

        final WorldParameters wp = new WorldParameters();
        wp.frame_period = 5.0;

        /* Load wave file (using jFloatWavIO) */
        final double[] x = WavIO.sGetSignal(inputFilename)[0];
        final int nbits = WavIO.sGetFormat(inputFilename).getSampleSizeInBits();
        wp.fs = (int) WavIO.sGetFormat(inputFilename).getFrameRate();
        displayInformation(x.length, wp.fs, nbits);
        /* Load wave file */

        // Analysis-Synthesis with no option. ---------------------------------------
        double[] y_NoOption = noOptionSynthesis(x, wp.fs);
        WavIO.sOutputData("00NoOption" + outputFilename, nbits, wp.fs, y_NoOption);

        //---------------------------------------------------------------------------
        // F0 estimation (DIO)
        f0EstimationDio(x, wp);

        // Spectral envelope estimation
        spectralEnvelopeEstimation(x, wp);

        // Aperiodicity estimation by D4C
        aperiodicityEstimation(x, wp);

        //---------------------------------------------------------------------------
        // Synthesis part
        // There are three samples in speech synthesis
        // 1: Conventional synthesis
        //---------------------------------------------------------------------------
        // Synthesis 1 (conventional synthesis)
        double[] y = waveformSynthesis(wp);
        WavIO.sOutputData("01" + outputFilename, nbits, wp.fs, y);
    }

    private TestApp() {
        throw new IllegalStateException("TestApp isn't allowed to create instance.");
    }

    /**
     * struct for WORLD
     * This struct is an option.
     * Users are NOT forced to use this struct.
     */
    private static final class WorldParameters {
        public double frame_period;
        public int fs;

        public double[] f0;
        public double[] temporal_positions;

        public double[][] spectrogram;
        public double[][] aperiodicity;
        public int fft_size;
    }


    private static void displayInformation(
        final int x_length, final int fs, final int nbits
    ) {
        final double lengthTime = (double) x_length / fs;

        System.out.println("File information");
        System.out.println("Sampling : " + fs + " Hz " + nbits + " Bit");
        System.out.println("Length " + x_length + " [sample]");
        System.out.println("Length " + String.format("%.6f", lengthTime) + " [sec]");
        System.out.println("");
    }


    private static double[] noOptionSynthesis(final double[] x, final int fs) {
        System.out.println("Analysis & Synthesis with no option.");
        final long elapsed_time = System.currentTimeMillis();

        final double[][] f0Info = Dio.estimateF0(x, fs);
        final double[] _f0 = f0Info[0];
        final double[] t = f0Info[1];
        final double[] f0 = StoneMask.refineF0(x, _f0, t, fs);
        final double[][] sp = CheapTrick.estimateSp(x, f0, t, fs);
        final double[][] ap = D4C.estimateAp(x, f0, t, fs);
        final double[] ret = Synthesis.getSignal(f0, sp, ap, fs);

        System.out.printf("A & S with no option : %d [msec]\n",
            System.currentTimeMillis() - elapsed_time
        );
        System.out.println("");
        return ret;
    }


    private static void f0EstimationDio(final double[] x, final WorldParameters wp) {
        final Dio.Option option = new Dio.Option();

        // Modification of the option
        option.frame_period = wp.frame_period;

        // Valuable option.speed represents the ratio for downsampling.
        // The signal is downsampled to fs / speed Hz.
        // If you want to obtain the accurate result, speed should be set to 1.
        option.speed = 1;

        // You can set the f0_floor below ConstantNumbers.kFloorF0.
        option.f0_floor = 40.0;

        // You can give a positive real number as the threshold.
        // Most strict value is 0, but almost all results are counted as unvoiced.
        // The value from 0.02 to 0.2 would be reasonable.
        option.allowed_range = 0.1;

        System.out.println("Analysis");
        long elapsed_time = System.currentTimeMillis();
        final double[][] f0Info = Dio.estimateF0(x, wp.fs, option);
        System.out.printf("DIO: %d [msec]\n", System.currentTimeMillis() - elapsed_time);

        final double[] f0_Dio = f0Info[0];
        final double[] temporal_positions = f0Info[1];

        // StoneMask is carried out to improve the estimation performance.
        elapsed_time = System.currentTimeMillis();
        final double[] 
            refined_f0 = StoneMask.refineF0(x, f0_Dio, temporal_positions, wp.fs);
        System.out.printf("StoneMask: %d [msec]\n", System.currentTimeMillis() - elapsed_time);

        wp.f0 = refined_f0;
        wp.temporal_positions = temporal_positions;
    }


    private static void spectralEnvelopeEstimation(double[] x, WorldParameters wp) {
        CheapTrick.Option option = new CheapTrick.Option(wp.fs);

        // Default value was modified to -0.15.
        option.q1 = -0.15;

        // Important notice (2017/01/02)
        // You can set the fft_size.
        // Default is GetFFTSizeForCheapTrick(world_parameters.fs, option);
        // When fft_size changes from default value,
        // a replaced f0_floor will be used in CheapTrick().
        // The lowest F0 that WORLD can work as expected is determined
        // by the following : 3.0 * fs / fft_size.
        option.f0_floor = 71.0;
        option.fft_size =
            CheapTrick.getFFTSizeForCheapTrick(wp.fs, option.f0_floor);
        // We can directly set fft_size.
        //option.fft_size = 1024;

        long elapsed_time = System.currentTimeMillis();
        double[][] sp = CheapTrick.estimateSp(x, wp.f0, wp.temporal_positions, wp.fs, option);
        System.out.printf("CheapTrick: %d [msec]\n", System.currentTimeMillis() - elapsed_time);

        wp.fft_size = option.fft_size;
        wp.spectrogram = sp;
    }


    private static void aperiodicityEstimation(double[] x, WorldParameters wp) {
        D4C.Option option = new D4C.Option();

        // Comment was modified because it was confusing (2017/12/10).
        // It is used to determine the aperiodicity in whole frequency band.
        // D4C identifies whether the frame is voiced segment even if it had an F0.
        // If the estimated value falls below the threshold,
        // the aperiodicity in whole frequency band will set to 1.0.
        // If you want to use the conventional D4C, please set the threshold to 0.0.
        option.threshold = 0.85;

        long elapsed_time = System.currentTimeMillis();
        double[][] ap = D4C.estimateAp(
            x, wp.f0, wp.temporal_positions, wp.fs, wp.fft_size, option);
        System.out.printf("D4C: %d [msec]\n", System.currentTimeMillis() - elapsed_time);

        wp.aperiodicity = ap;
    }


    private static double[] waveformSynthesis(WorldParameters wp) {
        System.out.printf("\nSynthesis 1 (conventional algorithm)\n");

        // Synthesis by the aperiodicity
        long elapsed_time = System.currentTimeMillis();
        double[] y = Synthesis.getSignal(
            wp.f0, wp.spectrogram, wp.aperiodicity, wp.fs, wp.frame_period);
        System.out.printf("WORLD: %d [msec]\n", System.currentTimeMillis() - elapsed_time);

        return y;
    }
}