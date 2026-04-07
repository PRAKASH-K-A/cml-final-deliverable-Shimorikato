package com.helesto.service;

import javax.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * G3-M3: Black-Scholes Options Pricing Service
 * - Black-Scholes formula implementation
 * - Greeks calculation (Delta, Gamma, Theta, Vega, Rho)
 * - Numerical stability for edge cases
 * - Implied volatility solver
 */
@ApplicationScoped
public class BlackScholesPricingService {

    private static final Logger LOG = LoggerFactory.getLogger(BlackScholesPricingService.class);
    
    // Constants for numerical stability
    private static final double MIN_VOLATILITY = 0.0001;
    private static final double MAX_VOLATILITY = 10.0;
    private static final double MIN_TIME = 0.00001; // ~5 minutes to expiry
    private static final double SQRT_2PI = Math.sqrt(2 * Math.PI);
    
    /**
     * Calculate call option price using Black-Scholes formula
     * 
     * @param S Spot price of underlying
     * @param K Strike price
     * @param T Time to expiration in years
     * @param r Risk-free interest rate (annualized)
     * @param sigma Volatility (annualized)
     * @return Call option price
     */
    public double callPrice(double S, double K, double T, double r, double sigma) {
        // Validate inputs for numerical stability
        if (S <= 0 || K <= 0) {
            LOG.warn("Invalid input: S={}, K={}", S, K);
            return 0;
        }
        
        // Handle near-zero time to expiry
        if (T < MIN_TIME) {
            return Math.max(0, S - K);
        }
        
        // Clamp volatility
        sigma = clampVolatility(sigma);
        
        double d1 = d1(S, K, T, r, sigma);
        double d2 = d2(d1, sigma, T);
        
        double callVal = S * normalCDF(d1) - K * Math.exp(-r * T) * normalCDF(d2);
        
        return Math.max(0, callVal);
    }
    
    /**
     * Calculate put option price using Black-Scholes formula
     */
    public double putPrice(double S, double K, double T, double r, double sigma) {
        if (S <= 0 || K <= 0) {
            LOG.warn("Invalid input: S={}, K={}", S, K);
            return 0;
        }
        
        if (T < MIN_TIME) {
            return Math.max(0, K - S);
        }
        
        sigma = clampVolatility(sigma);
        
        double d1 = d1(S, K, T, r, sigma);
        double d2 = d2(d1, sigma, T);
        
        double putVal = K * Math.exp(-r * T) * normalCDF(-d2) - S * normalCDF(-d1);
        
        return Math.max(0, putVal);
    }
    
    /**
     * Calculate all Greeks for a call option
     */
    public Greeks callGreeks(double S, double K, double T, double r, double sigma) {
        Greeks greeks = new Greeks();
        
        if (S <= 0 || K <= 0 || T < MIN_TIME) {
            LOG.warn("Invalid input for Greeks: S={}, K={}, T={}", S, K, T);
            return greeks;
        }
        
        sigma = clampVolatility(sigma);
        
        double d1 = d1(S, K, T, r, sigma);
        double d2 = d2(d1, sigma, T);
        double sqrtT = Math.sqrt(T);
        double discount = Math.exp(-r * T);
        
        greeks.delta = normalCDF(d1);
        greeks.gamma = normalPDF(d1) / (S * sigma * sqrtT);
        greeks.theta = (-S * normalPDF(d1) * sigma / (2 * sqrtT) 
                       - r * K * discount * normalCDF(d2)) / 365; // Per day
        greeks.vega = S * normalPDF(d1) * sqrtT / 100; // Per 1% vol change
        greeks.rho = K * T * discount * normalCDF(d2) / 100; // Per 1% rate change
        
        return greeks;
    }
    
    /**
     * Calculate all Greeks for a put option
     */
    public Greeks putGreeks(double S, double K, double T, double r, double sigma) {
        Greeks greeks = new Greeks();
        
        if (S <= 0 || K <= 0 || T < MIN_TIME) {
            return greeks;
        }
        
        sigma = clampVolatility(sigma);
        
        double d1 = d1(S, K, T, r, sigma);
        double d2 = d2(d1, sigma, T);
        double sqrtT = Math.sqrt(T);
        double discount = Math.exp(-r * T);
        
        greeks.delta = normalCDF(d1) - 1; // Negative for puts
        greeks.gamma = normalPDF(d1) / (S * sigma * sqrtT); // Same as call
        greeks.theta = (-S * normalPDF(d1) * sigma / (2 * sqrtT) 
                       + r * K * discount * normalCDF(-d2)) / 365;
        greeks.vega = S * normalPDF(d1) * sqrtT / 100; // Same as call
        greeks.rho = -K * T * discount * normalCDF(-d2) / 100;
        
        return greeks;
    }
    
    /**
     * Calculate implied volatility using Newton-Raphson method
     */
    public double impliedVolatility(double marketPrice, double S, double K, double T, 
                                   double r, boolean isCall) {
        if (marketPrice <= 0 || S <= 0 || K <= 0 || T < MIN_TIME) {
            return 0;
        }
        
        // Initial guess based on ATM approximation
        double sigma = Math.sqrt(2 * Math.abs(Math.log(S / K) + r * T) / T);
        sigma = Math.max(0.1, Math.min(sigma, 1.0)); // Start between 10% and 100%
        
        double tolerance = 0.0001;
        int maxIterations = 100;
        
        for (int i = 0; i < maxIterations; i++) {
            double price = isCall ? callPrice(S, K, T, r, sigma) : putPrice(S, K, T, r, sigma);
            double diff = price - marketPrice;
            
            if (Math.abs(diff) < tolerance) {
                return sigma;
            }
            
            // Vega for Newton-Raphson step
            double d1 = d1(S, K, T, r, sigma);
            double vega = S * normalPDF(d1) * Math.sqrt(T);
            
            if (vega < 0.0001) {
                // Vega too small, try bisection fallback
                break;
            }
            
            sigma = sigma - diff / vega;
            sigma = clampVolatility(sigma);
        }
        
        // Fallback to bisection method
        return impliedVolatilityBisection(marketPrice, S, K, T, r, isCall);
    }
    
    /**
     * Bisection method fallback for implied volatility
     */
    private double impliedVolatilityBisection(double marketPrice, double S, double K, 
                                              double T, double r, boolean isCall) {
        double low = MIN_VOLATILITY;
        double high = MAX_VOLATILITY;
        double tolerance = 0.0001;
        int maxIterations = 100;
        
        for (int i = 0; i < maxIterations; i++) {
            double mid = (low + high) / 2;
            double price = isCall ? callPrice(S, K, T, r, mid) : putPrice(S, K, T, r, mid);
            double diff = price - marketPrice;
            
            if (Math.abs(diff) < tolerance) {
                return mid;
            }
            
            if (diff > 0) {
                high = mid;
            } else {
                low = mid;
            }
        }
        
        return (low + high) / 2;
    }
    
    /**
     * Complete option pricing result
     */
    public OptionPriceResult priceOption(double S, double K, double T, double r, 
                                         double sigma, boolean isCall) {
        OptionPriceResult result = new OptionPriceResult();
        result.spotPrice = S;
        result.strikePrice = K;
        result.timeToExpiry = T;
        result.riskFreeRate = r;
        result.volatility = sigma;
        result.isCall = isCall;
        
        if (isCall) {
            result.theoreticalPrice = callPrice(S, K, T, r, sigma);
            result.intrinsicValue = Math.max(0, S - K);
            result.greeks = callGreeks(S, K, T, r, sigma);
        } else {
            result.theoreticalPrice = putPrice(S, K, T, r, sigma);
            result.intrinsicValue = Math.max(0, K - S);
            result.greeks = putGreeks(S, K, T, r, sigma);
        }
        
        result.timeValue = result.theoreticalPrice - result.intrinsicValue;
        
        return result;
    }
    
    // ================== Helper Functions ==================
    
    private double d1(double S, double K, double T, double r, double sigma) {
        double sqrtT = Math.sqrt(T);
        return (Math.log(S / K) + (r + sigma * sigma / 2) * T) / (sigma * sqrtT);
    }
    
    private double d2(double d1, double sigma, double T) {
        return d1 - sigma * Math.sqrt(T);
    }
    
    /**
     * Standard normal cumulative distribution function
     * Using Abramowitz and Stegun approximation for numerical stability
     */
    private double normalCDF(double x) {
        // Handle extreme values
        if (x < -10) return 0;
        if (x > 10) return 1;
        
        // Constants for approximation
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;
        
        int sign = x < 0 ? -1 : 1;
        x = Math.abs(x) / Math.sqrt(2);
        
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        
        return 0.5 * (1.0 + sign * y);
    }
    
    /**
     * Standard normal probability density function
     */
    private double normalPDF(double x) {
        return Math.exp(-x * x / 2) / SQRT_2PI;
    }
    
    private double clampVolatility(double sigma) {
        return Math.max(MIN_VOLATILITY, Math.min(MAX_VOLATILITY, sigma));
    }
    
    // ================== Result Classes ==================
    
    public static class Greeks {
        public double delta;  // Price sensitivity to underlying
        public double gamma;  // Delta sensitivity to underlying
        public double theta;  // Price sensitivity to time (per day)
        public double vega;   // Price sensitivity to volatility (per 1%)
        public double rho;    // Price sensitivity to interest rate (per 1%)
        
        @Override
        public String toString() {
            return String.format("Greeks{delta=%.4f, gamma=%.4f, theta=%.4f, vega=%.4f, rho=%.4f}",
                    delta, gamma, theta, vega, rho);
        }
    }
    
    public static class OptionPriceResult {
        public double spotPrice;
        public double strikePrice;
        public double timeToExpiry;
        public double riskFreeRate;
        public double volatility;
        public boolean isCall;
        public double theoreticalPrice;
        public double intrinsicValue;
        public double timeValue;
        public Greeks greeks;
        
        @Override
        public String toString() {
            return String.format("OptionPrice{%s K=%.2f, price=%.4f, IV=%.2f, TV=%.2f, %s}",
                    isCall ? "CALL" : "PUT", strikePrice, theoreticalPrice, 
                    intrinsicValue, timeValue, greeks);
        }
    }
}
