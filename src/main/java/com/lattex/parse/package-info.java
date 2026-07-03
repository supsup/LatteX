/**
 * LaTeX math tokenizer + recursive-descent parser producing the
 * {@link com.lattex.parse.MathNode} ADT.
 *
 * <p>{@link com.lattex.parse.MathParser#parse(String)} is the entry point;
 * malformed input throws {@link com.lattex.parse.MathSyntaxException}. Written
 * clean-room from the LaTeX/TeX grammar (TeXbook) — no GPL renderer consulted.
 */
package com.lattex.parse;
