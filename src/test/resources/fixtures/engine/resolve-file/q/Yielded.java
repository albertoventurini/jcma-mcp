package q;

/**
 * Referenced <em>only</em> inside {@link Yielding}'s switch-expression {@code yield} block arm, so a
 * resolved {@code TYPEREF → q.Yielded} can only come from recovering symbols <em>inside</em> the yield
 * arm — which {@code LanguageLevel.RAW}'s error recovery discarded entirely. Locks the in-yield
 * recovery that parsing at a real language level (validator stripped) uniquely gives.
 */
public class Yielded {}
