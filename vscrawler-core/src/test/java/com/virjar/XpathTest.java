package com.virjar;

import com.virjar.vscrawler.core.selector.xpath.core.XpathEvaluator;

/**
 * Created by virjar on 17/6/5.
 */
public class XpathTest {
    public static void main(String[] args) {
        XpathEvaluator xpathEvaluator = XpathEvaluator.compile("//meta[@charset]");
        XpathEvaluator evaluator = XpathEvaluator.compile("bookstore//book[1]");
    }
}
