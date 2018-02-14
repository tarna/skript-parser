package io.github.syst3ms.skriptparser.pattern;

import io.github.syst3ms.skriptparser.lang.Expression;
import io.github.syst3ms.skriptparser.lang.Literal;
import io.github.syst3ms.skriptparser.lang.Variable;
import io.github.syst3ms.skriptparser.lang.VariableString;
import io.github.syst3ms.skriptparser.parsing.SkriptParser;
import io.github.syst3ms.skriptparser.parsing.SyntaxParser;
import io.github.syst3ms.skriptparser.types.PatternType;
import io.github.syst3ms.skriptparser.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * A variable/expression, declared in syntax using {@literal %type%}
 * Has :
 * <ul>
 * <li>a {@link List} of {@link PatternType}</li>
 * <li>a field determining what type of values this expression accepts : literals, expressions or both ({@literal %*type%}, {@literal %~type%} and {@literal %type%} respectively)</li>
 * <li>whether the expression resorts to default expressions or not, defaulting to {@literal null} instead</li>
 * </ul>
 */
public class ExpressionElement implements PatternElement {
    private List<PatternType<?>> types;
    private Acceptance acceptance;
    private boolean nullable;

    public ExpressionElement(List<PatternType<?>> types, Acceptance acceptance, boolean nullable) {
        this.types = types;
        this.acceptance = acceptance;
        this.nullable = nullable;
    }

    @Override
    public int match(String s, int index, SkriptParser parser) {
        if (parser.getOriginalElement().equals(this))
            parser.advanceInPattern();
        PatternType<?>[] typeArray = types.toArray(new PatternType<?>[types.size()]);
        if (s.charAt(index) == '(') {
            String enclosed = StringUtils.getEnclosedText(s, '(', ')', index);
            /*
             * We don't want to return here, a single bracket could come from a syntax (albeit a stupid one)
             * We also want to continue the code in any case
             */
            if (enclosed != null) {
                Expression<?> expression = parse(s, parser.getOriginalElement(), typeArray);
                if (expression != null) {
                    parser.addExpression(expression);
                    return index + s.length();
                }
            }
        }
        List<PatternElement> flattened = parser.flatten(parser.getOriginalElement());
        List<PatternElement> possibleInputs = parser.getPossibleInputs(flattened.subList(parser.getPatternIndex(), flattened.size()));
        for (PatternElement possibleInput : possibleInputs) {
            if (possibleInput instanceof TextElement) {
                String text = ((TextElement) possibleInput).getText();
                if (text.equals("")) { // End of line
                    String toParse = s.substring(index);
                    Expression<?> expression = parse(toParse, parser.getOriginalElement(), typeArray);
                    if (expression != null) {
                        parser.addExpression(expression);
                        return index + toParse.length();
                    }
                    return -1;
                }
                int i = s.indexOf(text, index);
                if (i == -1) {
                    continue;
                }
                String toParse = s.substring(index, i).trim();
                Expression<?> expression = parse(toParse, parser.getOriginalElement(), typeArray);
                if (expression != null) {
                    parser.addExpression(expression);
                    return index + toParse.length();
                }
            } else if (possibleInput instanceof RegexGroup) {
                Matcher m = ((RegexGroup) possibleInput).getPattern().matcher(s).region(index, s.length());
                while (m.lookingAt()) {
                    int i = m.start();
                    if (i == -1) {
                        continue;
                    }
                    String toParse = s.substring(index, i);
                    Expression<?> expression = parse(toParse, parser.getOriginalElement(), typeArray);
                    if (expression != null) {
                        parser.addExpression(expression);
                        return index + toParse.length();
                    }
                }
            } else {
                assert possibleInput instanceof ExpressionElement;
                List<PatternElement> nextPossibleInputs = parser
                    .getPossibleInputs(flattened.subList(parser.getPatternIndex() + 1, flattened.size()));
                if (nextPossibleInputs.stream()
                                      .anyMatch(pe -> !(pe instanceof TextElement))) { // Let's not get that deep
                    continue;
                }
                for (PatternElement nextPossibleInput : nextPossibleInputs) {
                    String text = ((TextElement) nextPossibleInput).getText();
                    if (text.equals("")) {
                        String rest = s.substring(index, s.length());
                        List<String> splits = getSplits(rest);
                        for (String split : splits) {
                            int i = s.indexOf(split, index);
                            if (i != -1) {
                                String toParse = s.substring(index, i);
                                Expression<?> expression = parse(toParse, parser.getOriginalElement(), typeArray);
                                if (expression != null) {
                                    parser.addExpression(expression);
                                    return index + toParse.length();
                                }
                            }
                        }
                        return -1;
                    } else {
                        int bound = s.indexOf(text, index);
                        if (bound == -1) {
                            continue;
                        }
                        String rest = s.substring(index, bound);
                        List<String> splits = getSplits(rest);
                        for (String split : splits) {
                            int i = s.indexOf(split, index);
                            if (i != -1) {
                                String toParse = s.substring(index, i);
                                Expression<?> expression = parse(toParse, parser.getOriginalElement(), typeArray);
                                if (expression != null) {
                                    parser.addExpression(expression);
                                    return index + toParse.length();
                                }
                            }
                        }
                    }
                }
            }
        }
        return -1;
    }

    private List<String> getSplits(String s) {
        List<String> splitted = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        char[] charArray = s.toCharArray();
        for (int i = 0; i < charArray.length; i++) {
            char c = charArray[i];
            if (c == ' ') {
                if (sb.length() > 0) {
                    splitted.add(sb.toString());
                    sb.setLength(0);
                }
            } else if (c == '(') {
                String enclosed = StringUtils.getEnclosedText(s, '(', ')', i);
                if (enclosed == null) {
                    sb.append('(');
                    continue;
                }
                sb.append('(').append(enclosed).append(')');
                i += enclosed.length() + 1;
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            splitted.add(sb.toString());
        }
        return splitted;
    }

    @SuppressWarnings("unchecked")
    private <T> Expression<? extends T> parse(String s, PatternElement originalPattern, PatternType<?>[] types) {
        for (PatternType<?> type : types) {
            Expression<? extends T> expression;
            if (type.equals(SyntaxParser.BOOLEAN_PATTERN_TYPE)) {
                // REMINDER : conditions call parseBooleanExpression straight away
                expression = (Expression<? extends T>) SyntaxParser.parseBooleanExpression(s, originalPattern.equals(SkriptParser.WHETHER_PATTERN));
            } else {
                expression = SyntaxParser.parseExpression(s, (PatternType<T>) type);
            }
            if (expression == null)
                continue;
            switch (acceptance) {
                case ALL:
                    break;
                case EXPRESSIONS_ONLY:
                    if (expression instanceof Literal) return null;
                    break;
                case LITERALS_ONLY:
                    if (expression instanceof VariableString && !((VariableString) expression).isSimple() || !(expression instanceof Literal)) {
                        return null;
                    }
                    break;
                case VARIABLES_ONLY:
                    if (!(expression instanceof Variable)) return null;
                    break;
            }
            return expression;
        }
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof ExpressionElement)) {
            return false;
        } else {
            ExpressionElement e = (ExpressionElement) obj;
            return types.equals(e.types) && acceptance == e.acceptance;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("%");
        if (nullable)
            sb.append('-');
        if (acceptance == Acceptance.EXPRESSIONS_ONLY) {
            sb.append('~');
        } else if (acceptance == Acceptance.LITERALS_ONLY) {
            sb.append('*');
        } else if (acceptance == Acceptance.VARIABLES_ONLY) {
            sb.append('^');
        }
        sb.append(
            String.join(
                "/",
                types.stream().map(PatternType::toString).toArray(CharSequence[]::new)
            )
        );
        return sb.append("%").toString();
    }

    public enum Acceptance {
        ALL,
        EXPRESSIONS_ONLY,
        LITERALS_ONLY, VARIABLES_ONLY
    }
}
