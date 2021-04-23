package org.hongxi.jaws.registry.support.command;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class RpcCommandUtils {

    private static final Logger log = LoggerFactory.getLogger(RpcCommandUtils.class);

    /**
     * 把指令字符串转为指令对象
     *
     * @param commandString
     * @return
     */
    public static RpcCommand stringToCommand(String commandString) {
        try {
            return JSONObject.parseObject(commandString, RpcCommand.class);
        } catch (Exception e) {
            log.error("指令配置错误：不是合法的JSON格式!");
            return null;
        }
    }

    /**
     * 指令对象转为string
     *
     * @param command
     * @return
     */
    public static String commandToString(RpcCommand command) {
        return JSONObject.toJSONString(command);
    }

    private static PatternEvaluator evaluator = new PatternEvaluator();

    public static boolean match(String expression, String path) {
        if (expression == null || expression.length() == 0) {
            return false;
        }
        return evaluator.match(expression, path);
    }


    /**
     * 匹配规则解析类
     */
    private static class PatternEvaluator {

        Pattern pattern = Pattern.compile("[a-zA-Z0-9_$.*]+");
        Set<Character> all = ImmutableSet.of('(', ')', '0', '1', '!', '&', '|');
        Map<Character, ImmutableSet<Character>> following = ImmutableMap.<Character, ImmutableSet<Character>>builder()
                .put('(', ImmutableSet.of('0', '1', '!')).put(')', ImmutableSet.of('|', '&', ')')).put('0', ImmutableSet.of('|', '&', ')'))
                .put('1', ImmutableSet.of('|', '&', ')')).put('!', ImmutableSet.of('(', '0', '1', '!'))
                .put('&', ImmutableSet.of('(', '0', '1', '!')).put('|', ImmutableSet.of('(', '0', '1', '!')).build();

        boolean match(String expression, String path) {

            // 匹配出每一项，求值，依据结果替换为0和1
            Matcher matcher = pattern.matcher(expression.replaceAll("\\s+", ""));
            StringBuffer buffer = new StringBuffer();
            while (matcher.find()) {
                String s = matcher.group();
                int idx = s.indexOf('*');
                if (idx != -1) {
                    matcher.appendReplacement(buffer, path.startsWith(s.substring(0, idx)) ? "1" : "0");
                } else {
                    matcher.appendReplacement(buffer, s.equals(path) ? "1" : "0");
                }
            }
            matcher.appendTail(buffer);
            String result1 = buffer.toString();

            // 嵌套链表结构用于处理圆括号
            LinkedList<LinkedList<Character>> outer = new LinkedList<>();
            LinkedList<Character> inner = new LinkedList<>();
            inner.push('#');
            outer.push(inner);

            int i = 0;
            int len = result1.length();
            while (outer.size() > 0 && i < len) {
                LinkedList<Character> sub = outer.peekLast();
                while (sub.size() > 0 && i < len) {
                    char curr = result1.charAt(i++);
                    support(curr);
                    char prev = sub.peekFirst();
                    if (prev != '#') {
                        supportFollowing(prev, curr);
                    }

                    switch (curr) {
                        case '(':
                            sub = new LinkedList<>();
                            sub.push('#');
                            outer.push(sub);
                            break;
                        case ')':
                            outer.removeFirst();
                            outer.peekFirst().push(evalWithinParentheses(sub));
                            sub = outer.peekFirst();
                            break;
                        default:
                            sub.push(curr);
                    }
                }
            }
            if (outer.size() != 1) {
                throw new IllegalArgumentException("语法错误, 可能圆括号没有闭合");
            }
            char result = evalWithinParentheses(outer.peekLast());
            return result == '1';
        }

        /**
         * 对圆括号内的子表达式求值
         *
         * @param list
         * @return
         */
        char evalWithinParentheses(LinkedList<Character> list) {
            char operand = list.pop();
            if (operand != '0' && operand != '1') {
                syntaxError();
            }

            // 处理!
            while (!list.isEmpty()) {
                char curr = list.pop();
                if (curr == '!') {
                    operand = operand == '0' ? '1' : '0';
                } else if (curr == '#') {
                    break;
                } else {
                    if (operand == '0' || operand == '1') {
                        list.addLast(operand);
                        list.addLast(curr);
                        operand = '\0';
                    } else {
                        operand = curr;
                    }
                }
            }
            list.addLast(operand);

            // 处理&
            list.addLast('#');
            operand = list.pop();
            while (!list.isEmpty()) {
                char curr = list.pop();
                if (curr == '&') {
                    char c = list.pop();
                    operand = (operand == '1' && c == '1') ? '1' : '0';
                } else if (curr == '#') {
                    break;
                } else {
                    if (operand == '0' || operand == '1') {
                        list.addLast(operand);
                        list.addLast(curr);
                        operand = '\0';
                    } else {
                        operand = curr;
                    }
                }
            }
            list.addLast(operand);

            // 处理|
            operand = '0';
            while (!list.isEmpty() && (operand = list.pop()) != '1');
            return operand;
        }

        void syntaxError() {
            throw new IllegalArgumentException("语法错误, 仅支持括号(),非!,与&,或|这几个运算符, 优先级依次递减.");
        }

        void syntaxError(String s) {
            throw new IllegalArgumentException("语法错误: " + s);
        }

        void support(char c) {
            if (!all.contains(c)) {
                syntaxError("不支持字符 " + c);
            }
        }

        void supportFollowing(char prev, char c) {
            if (!following.get(prev).contains(c)) {
                syntaxError("prev=" + prev + ", c=" + c);
            }
        }
    }
}