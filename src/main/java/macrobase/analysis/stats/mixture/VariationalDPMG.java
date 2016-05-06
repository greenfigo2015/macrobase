package macrobase.analysis.stats.mixture;

import macrobase.analysis.stats.distribution.MultivariateTDistribution;
import macrobase.conf.MacroBaseConf;
import macrobase.conf.MacroBaseDefaults;
import macrobase.datamodel.Datum;
import macrobase.util.AlgebraUtils;
import org.apache.commons.math3.linear.*;
import org.apache.commons.math3.special.Gamma;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Variational Dirichlet Process Mixture of Gaussians.
 */
public class VariationalDPMG extends MeanFieldGMM {
    private static final Logger log = LoggerFactory.getLogger(VariationalDPMG.class);

    // Number of truncated clusters.
    private int T;
    // Concentration parameter for the Dirichlet distribution.
    private double concentrationParameter;


    // Parameters describing stick lengths, i.e. shape parameters of Beta distributions.
    private double shapeParams[][];

    // Parameters for multinomial distributions for data points. i.e. density corresponding to each cluster (atom).
    private double[][] r;


    private List<MultivariateTDistribution> predictiveDistributions;

    private void updatePredictiveDistributions() {
        int dimension = atomLoc.get(0).getDimension();

        predictiveDistributions = new ArrayList<>(T);
        for (int t = 0; t < T; t++) {
            double scale = (atomDOF[t] + 1 - dimension) * atomBeta[t] / (1 + atomBeta[t]);
            RealMatrix ll = AlgebraUtils.invertMatrix(atomOmega.get(t).scalarMultiply(scale));
            // TODO: MultivariateTDistribution should support real values for 3rd parameters
            predictiveDistributions.add(new MultivariateTDistribution(atomLoc.get(t), ll, (int) (atomDOF[t] + 1 - dimension)));
        }
    }

    public VariationalDPMG(MacroBaseConf conf) {
        super(conf);
        T = conf.getInt(MacroBaseConf.DPM_TRUNCATING_PARAMETER, MacroBaseDefaults.DPM_TRUNCATING_PARAMETER);
        concentrationParameter = conf.getDouble(MacroBaseConf.DPM_CONCENTRATION_PARAMETER, MacroBaseDefaults.DPM_CONCENTRATION_PARAMETER);
    }

    // Keep this here so we have exactly the same behavior.
    @Override
    protected void initializeBaseNormalWishart(List<Datum> data) {
        int dimension = data.get(0).getMetrics().getDimension();
        baseNu = dimension;
        double[][] boundingBox = AlgebraUtils.getBoundingBox(data);
        double[] midpoints = new double[dimension];
        double[] dimensionWidth = new double[dimension];
        double R = 0;  // value of the widest dimension.
        for (int i = 0; i < dimension; i++) {
            dimensionWidth[i] = boundingBox[i][1] - boundingBox[i][0];
            midpoints[i] = boundingBox[i][0] + dimensionWidth[i];
            if (dimensionWidth[i] > R) {
                R = dimensionWidth[i];
            }
        }
        baseBeta = Math.pow(R, -2);
        baseLoc = new ArrayRealVector(midpoints);
        baseOmegaInverse = MatrixUtils.createRealIdentityMatrix(dimension);
    }


    private void initalizeAtoms(List<Datum> data) {
        atomOmega = new ArrayList<>(T);
        atomDOF = new double[T];
        atomBeta = new double[T];

        // atoms
        atomLoc = gonzalezInitializeMixtureCenters(data, T);
        for (int i = 0; i < T; i++) {
            // initialize betas as if all points are from the first cluster.
            atomBeta[i] = 1;
            atomDOF[i] = baseNu;
            atomOmega.add(0, AlgebraUtils.invertMatrix(baseOmegaInverse));
        }
    }

    private void initalizeBaseMixing() {
        // concentrationParameter has been set in the constructor.
    }

    private void initializeSticks() {
        // stick lengths
        shapeParams = new double[T][2];
        for (int i = 0; i < T; i++) {
            shapeParams[i][0] = 1;
            shapeParams[i][1] = concentrationParameter;
        }
    }

    @Override
    public void train(List<Datum> data) {
        int N = data.size();
        // 0. Initialize all approximating factors
        instantiateVariationalParameters(N, T);
        initConstants(data);
        initializeBaseNormalWishart(data);
        initalizeBaseMixing();
        initializeSticks();
        initalizeAtoms(data);

        // TODO: remove??!
        r = new double[N][T];
        List<RealMatrix> S;  // initialized and used in step 2.
        double logLikelihood = -Double.MAX_VALUE;
        double[] clusterWeight;
        List<RealVector> clusterMean;

        for (int iteration = 1; ; iteration++) {
            // 0. Initialize volatile parameters
            clusterMean = new ArrayList<>(T);
            for (int t = 0; t < T; t++) {
                clusterMean.add(new ArrayRealVector(new double[D]));
            }
            clusterWeight = new double[T];


            // 1. calculate expectation of densities of each point coming from individual clusters - r[n][k]
            // 1. Reevaluate r[][]
            double[] lnLambdaContribution = new double[T];
            double[] lnMixingContribution = new double[T];
            double cumulativeAlreadyAssigned = 0;
            // for (int t=0; t<atomLoc.size(); t++) {
            for (int t = 0; t < T; t++) {
                // Calculate Per cluster 0.5 ln L_t - D/2 ln(2 pi) contributions.
                lnLambdaContribution[t] = dimensionLn2 + Math.log((new EigenDecomposition(atomOmega.get(t))).getDeterminant());
                for (int i = 0; i < D ; i++) {
                    lnLambdaContribution[t] += Gamma.digamma((atomDOF[t] - i) / 2);
                }
                lnLambdaContribution[t] /= 2;
                // Calculate Mixing coefficient contributions to r
                lnMixingContribution[t] = cumulativeAlreadyAssigned + (Gamma.digamma(shapeParams[t][0]) - Gamma.digamma(shapeParams[t][0] + shapeParams[t][1]));
                cumulativeAlreadyAssigned += Gamma.digamma(shapeParams[t][1]) - Gamma.digamma(shapeParams[t][0] + shapeParams[t][1]);
            }


            double lnXMuLambdaContribution;
            for (int n = 0; n < N; n++) {
                double normalizingConstant = 0;
                for (int t = 0; t < T; t++) {
                    RealVector _diff = data.get(n).getMetrics().subtract(atomLoc.get(t));
                    if (atomBeta[t] != 0) {
                        lnXMuLambdaContribution = D / atomBeta[t] + atomDOF[t] * _diff.dotProduct(atomOmega.get(t).operate(_diff));
                    } else {
                        lnXMuLambdaContribution = atomDOF[t] * _diff.dotProduct(atomOmega.get(t).operate(_diff));
                    }
                    r[n][t] = Math.exp(lnMixingContribution[t] - halfDimensionLn2Pi + lnLambdaContribution[t] - lnXMuLambdaContribution);
                    normalizingConstant += r[n][t];
                }
                for (int t = 0; t < atomLoc.size(); t++) {
                    if (normalizingConstant > 0) {
                        r[n][t] /= normalizingConstant;
                    }
                    // Calculate unnormalized cluster weight, cluster mean
                    clusterWeight[t] += r[n][t];
                    clusterMean.set(t, clusterMean.get(t).add(data.get(n).getMetrics().mapMultiply(r[n][t])));
                }
            }

            // 2. Reevaluate clusters based on densities that we have for each point.
            // 2. Reevaluate atoms and stick lengths.
            S = new ArrayList<>(T);

            for (int t = 0; t < T; t++) {
                S.add(new BlockRealMatrix(D, D));
                if (clusterWeight[t] > 0) {
                    clusterMean.set(t, clusterMean.get(t).mapDivide(clusterWeight[t]));
                } else {
                    continue;
                }
                for (int n = 0; n < N; n++) {
                    RealVector _diff = data.get(n).getMetrics().subtract(clusterMean.get(t));
                    S.set(t, S.get(t).add(_diff.outerProduct(_diff).scalarMultiply(r[n][t])));
                }
                S.set(t, S.get(t).scalarMultiply(1 / clusterWeight[t]));
            }

            for (int t = 0; t < atomLoc.size(); t++) {
                shapeParams[t][0] = 1;
                shapeParams[t][1] = concentrationParameter;
                for (int n = 0; n < N; n++) {
                    shapeParams[t][0] += r[n][t];
                    for (int j = t + 1; j < T; j++) {
                        shapeParams[t][1] += r[n][j];
                    }
                }
                atomBeta[t] = baseBeta + clusterWeight[t];
                atomLoc.set(t, baseLoc.mapMultiply(baseBeta).add(clusterMean.get(t).mapMultiply(clusterWeight[t])).mapDivide(atomBeta[t]));
                atomDOF[t] = baseNu + 1 + clusterWeight[t];
                RealMatrix wInverse = baseOmegaInverse.add(
                        S.get(t).scalarMultiply(clusterWeight[t])).add(
                        clusterMean.get(t).outerProduct(clusterMean.get(t)).scalarMultiply(baseBeta * clusterWeight[t] / (baseBeta + clusterWeight[t])));
                atomOmega.set(t, AlgebraUtils.invertMatrix(wInverse));
            }


            updatePredictiveDistributions();

            double oldLogLikelihood = logLikelihood;
            logLikelihood = 0;
            for (int n = 0; n < N; n++) {
                logLikelihood += Math.log(score(data.get(n)));
            }

            log.debug("log likelihood after iteration {} is {}", iteration, logLikelihood);

            if (iteration >= maxIterationsToConverge) {
                log.debug("Breaking because have already run {} iterations", iteration);
                break;
            }

            double improvement = (logLikelihood - oldLogLikelihood) / (-logLikelihood);
            if (improvement >= 0 && improvement < this.progressCutoff) {
                log.debug("Breaking because improvement was {} percent", improvement * 100);
                break;
            } else {
                log.debug("improvement is : {}%", improvement * 100);
            }
            log.debug(".........................................");
        }
    }

    private void instantiateVariationalParameters(int numPoints, int maxNumClusters) {
    }

    @Override
    public double score(Datum datum) {
        double density = 0;
        double[] stickLengths = getClusterWeights();
        for (int i = 0; i < predictiveDistributions.size(); i++) {
            density += stickLengths[i] * predictiveDistributions.get(i).density(datum.getMetrics());
        }
        return density;
    }

    @Override
    public double getZScoreEquivalent(double zscore) {
        return 0;
    }

    @Override
    public double[] getClusterWeights() {
        double[] proportions = new double[T];
        double stickRemaining = 1;
        double expectedBreak;
        for (int i = 0; i < T; i++) {
            expectedBreak = stickRemaining / (1 + shapeParams[i][1] / shapeParams[i][0]);
            stickRemaining -= expectedBreak;
            proportions[i] = expectedBreak;
        }
        return proportions;
    }

    @Override
    public List<RealVector> getClusterCenters() {
        return atomLoc;
    }

    @Override
    public List<RealMatrix> getClusterCovariances() {
        List<RealMatrix> covariances = new ArrayList<>(T);
        for (int t = 0; t < T; t++) {
            covariances.add(AlgebraUtils.invertMatrix(atomOmega.get(t).scalarMultiply(atomDOF[t])));
        }
        return covariances;
    }

    @Override
    public double[] getClusterProbabilities(Datum d) {
        double[] probas = new double[T];
        double[] weights = getClusterWeights();
        double normalizingConstant = 0;
        for (int i = 0; i < T; i++) {
            probas[i] = weights[i] * predictiveDistributions.get(i).density(d.getMetrics());
            normalizingConstant += probas[i];
        }
        for (int i = 0; i < T; i++) {
            probas[i] /= normalizingConstant;
        }
        return probas;
    }
}
