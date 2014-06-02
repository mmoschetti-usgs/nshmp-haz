package org.opensha.gmm;

import static java.lang.Math.log10;
import static org.opensha.gmm.GMM_Utils.BASE_10_TO_E;
import static org.opensha.util.MathUtils.hypot;

import org.opensha.calc.ScalarGroundMotion;

/**
 * Implementation of the subduction interface ground motion model by Atkinson
 * &amp; Macias (2009). This implementation matches that used in the 2014 USGS
 * NSHMP.
 * 
 * <p><b>Note:</b> Direct instantiation of {@code GroundMotionModel}s is
 * prohibited. Use {@link GMM#instance(IMT)} to retrieve an instance for a
 * desired {@link IMT}.</p>
 * 
 * <p><b>Implementation notes:</b> <ul><li>This is currently only applicable to
 * vs760; there is a suggestion in the NSHM fortran that the AB08 non-linear
 * site amplification model should be used; this needs to be revisited and would
 * require implementation of pgaRock (TODO).</li><li>NSHM fortran implementation
 * converts 0.13Hz to 7.7s; this implementation uses 7.5s instead.</li></ul></p>
 * 
 * <p><b>Reference:</b> Atkinson, G.M. and Macias, D.M., 2009, Predicted Ground
 * Motions for Great Interface Earthquakes in the Cascadia Subduction Zone:
 * Bulletin of the Seismological Society of America, v. 99, p. 1552-1578.</p>
 * 
 * <p><b>Component:</b> geometric mean of two horizontal components</p>
 * 
 * @author Peter Powers
 * @see GMM#AM_09_INTER
 */
public final class AtkinsonMacias_2009 implements GroundMotionModel {
	
	static final String NAME = "Atkinson \u0026 Macias (2009): Interface";
	
	static final CoefficientContainer CC = new CoefficientContainer("AM09.csv", Coeffs.class);
	
	static class Coeffs extends Coefficients {
		double c0, c1, c2, c3, c4, sig;
	}
	
	// author declared constants
	private static final double GFAC = 6.8875526;

	// implementation constants
	// none
	
	private final Coeffs coeffs;
	
	AtkinsonMacias_2009(IMT imt) {
		coeffs = (Coeffs) CC.get(imt);
	}
	
	@Override
	public final ScalarGroundMotion calc(GMM_Source props) {
		double mean = calcMean(coeffs, props.Mw, props.rRup);
		double sigma = coeffs.sig * BASE_10_TO_E;
		return DefaultScalarGroundMotion.create(mean, sigma);
	}
		
	// SF2 variable of AB06 needs to be provided by subclasses via
	private static final double calcMean(Coeffs c, double Mw, double rRup) {

		double h = (Mw * Mw) - (3.1 * Mw) - 14.55;
		double dM = Mw - 8.0;
		double gnd = c.c0 + (c.c3 * dM) + (c.c4 * dM * dM);
		double r = hypot(rRup, h);
		gnd += c.c1 * log10(r) + c.c2 * r;

		return gnd * BASE_10_TO_E - GFAC;
	}

}