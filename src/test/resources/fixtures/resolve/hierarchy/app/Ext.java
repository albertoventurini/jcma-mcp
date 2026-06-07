package app;

import java.util.ArrayList;

/**
 * The external-supertype case: {@code extends java.util.ArrayList} (a JDK type with no project source),
 * so the {@code EXTENDS} edge's {@code dst} must be a <b>phantom</b> node keyed by signature, not a
 * project moniker. The {@code Base} field is incidental — it makes this file a candidate of the simple
 * name {@code Base}, so the same {@code find_references(Base)} trigger warms it.
 */
public class Ext extends ArrayList<String> {

    Base b;
}
