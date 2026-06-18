package q;

/**
 * References the deepest record-nested type through the full record-container path. The single field's
 * type node carries every intermediate hop as a nested {@code ClassOrInterfaceType}, so the scan emits
 * one type-reference per level — exercising the fallback at depths 1..4.
 */
public class NestUser {

    Nest.Mid.Deep.Deeper.Deepest deepest;
}
