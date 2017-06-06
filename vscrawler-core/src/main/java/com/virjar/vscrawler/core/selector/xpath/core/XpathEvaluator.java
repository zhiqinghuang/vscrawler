package com.virjar.vscrawler.core.selector.xpath.core;
/*
 * Copyright 2014 Wang Haomiao<et.tw@163.com> Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and limitations under the
 * License.
 */

import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.virjar.vscrawler.core.selector.xpath.exception.NoSuchAxisException;
import com.virjar.vscrawler.core.selector.xpath.model.JXNode;
import com.virjar.vscrawler.core.selector.xpath.model.Predicate;
import com.virjar.vscrawler.core.selector.xpath.model.XpathNode;
import com.virjar.vscrawler.core.selector.xpath.util.CommonUtil;
import com.virjar.vscrawler.core.selector.xpath.util.ScopeEm;

import lombok.Getter;

/**
 * @author github.com/zhegexiaohuozi [seimimaster@gmail.com]
 * @author virjar
 * @since 0.0.1
 */
public class XpathEvaluator {
    private List<List<XpathNode>> xpathNodes = Lists.newLinkedList();
    @Getter
    private String originXpath;
    private static final Splitter combineXpathSplitter = Splitter.on("|").omitEmptyStrings().trimResults();

    private XpathEvaluator(String xpathStr) {
        originXpath = xpathStr;
        for (String sampleXpath : combineXpathSplitter.split(xpathStr)) {
            xpathNodes.add(getXpathNodeTree(sampleXpath));
        }
    }

    public static XpathEvaluator compile(String xpath) {
        return new XpathEvaluator(xpath);
    }

    /**
     * xpath解析器的总入口，同时预处理，如‘|’
     *
     * @param root
     * @return
     */
    public List<JXNode> xpathSelect(Elements root) {
        List<JXNode> rs = Lists.newLinkedList();
        for (List<XpathNode> chiXp : xpathNodes) {
            rs.addAll(evaluate(chiXp, root));
        }
        return rs;

    }

    /**
     * 获取xpath解析语法树
     *
     * @param xpath
     * @return
     */
    public List<XpathNode> getXpathNodeTree(String xpath) {
        char[] chars = xpath.toCharArray();
        NodeTreeBuilderStateMachine st = new NodeTreeBuilderStateMachine();
        while (st.state != NodeTreeBuilderStateMachine.BuilderState.END) {
            st.state.parser(st, chars);
        }
        return st.context.getXpathTr();
    }

    /**
     * 根据xpath求出结果
     *
     * @param root
     * @return
     */
    private List<JXNode> evaluate(List<XpathNode> simpleNodes, Elements root) {
        List<JXNode> res = new LinkedList<JXNode>();
        Elements context = root;
        for (int i = 0; i < simpleNodes.size(); i++) {
            XpathNode n = simpleNodes.get(i);
            LinkedList<Element> contextTmp = new LinkedList<Element>();

            // 递归查找
            if (n.getScopeEm() == ScopeEm.RECURSIVE || n.getScopeEm() == ScopeEm.CURREC) {
                if (n.getTagName().startsWith("@")) {
                    for (Element e : context) {
                        // 处理上下文自身节点
                        String key = n.getTagName().substring(1);
                        if (key.equals("*")) {
                            res.add(JXNode.t(e.attributes().toString()));
                        } else {
                            String value = e.attr(key);
                            if (StringUtils.isNotBlank(value)) {
                                res.add(JXNode.t(value));
                            }
                        }
                        // 处理上下文子代节点
                        for (Element dep : e.getAllElements()) {
                            if (key.equals("*")) {
                                res.add(JXNode.t(dep.attributes().toString()));
                            } else {
                                String value = dep.attr(key);
                                if (StringUtils.isNotBlank(value)) {
                                    res.add(JXNode.t(value));
                                }
                            }
                        }
                    }
                    // 这里是抽取动作,应该结束才对

                } else if (n.getTagName().endsWith("()")) {
                    // 递归执行方法默认只支持text()
                    res.add(JXNode.t(context.text()));
                } else {
                    Elements searchRes = context.select(n.getTagName());
                    for (Element e : searchRes) {
                        Element filterR = filter(e, n);
                        if (filterR != null) {
                            contextTmp.add(filterR);
                        }
                    }
                    context = new Elements(contextTmp);
                    if (i == xpathNodes.size() - 1) {
                        for (Element e : contextTmp) {
                            res.add(JXNode.e(e));
                        }
                    }
                }

            } else {
                if (n.getTagName().startsWith("@")) {
                    for (Element e : context) {
                        String key = n.getTagName().substring(1);
                        if (key.equals("*")) {
                            res.add(JXNode.t(e.attributes().toString()));
                        } else {
                            String value = e.attr(key);
                            if (StringUtils.isNotBlank(value)) {
                                res.add(JXNode.t(value));
                            }
                        }
                    }
                } else if (n.getTagName().endsWith("()")) {
                    // TODO 函数是否存在的check
                    res = FunctionEnv.getSelectFunction(n.getTagName().substring(0, n.getTagName().length() - 2))
                            .call(context);
                    // res = (List<JXNode>) callFunc(n.getTagName().substring(0, n.getTagName().length() - 2), context);
                } else {
                    for (Element e : context) {
                        Elements filterScope = e.children();
                        if (StringUtils.isNotBlank(n.getAxis())) {
                            filterScope = getAxisScopeEls(n.getAxis(), e);
                        }
                        for (Element chi : filterScope) {
                            Element fchi = filter(chi, n);
                            if (fchi != null) {
                                contextTmp.add(fchi);
                            }
                        }
                    }
                    context = new Elements(contextTmp);
                    if (i == xpathNodes.size() - 1) {
                        for (Element e : contextTmp) {
                            res.add(JXNode.e(e));
                        }
                    }
                }
            }
        }
        return res;
    }

    /**
     * 元素过滤器
     *
     * @param e
     * @param xpathNode
     * @return
     */
    public Element filter(Element e, XpathNode xpathNode) {
        if (xpathNode.getTagName().equals("*") || xpathNode.getTagName().equals(e.nodeName())) {
            if (xpathNode.getPredicate() != null && StringUtils.isNotBlank(xpathNode.getPredicate().getValue())) {
                Predicate p = xpathNode.getPredicate();
                if (p.getOpEm() == null) {
                    if (p.getValue().matches("\\d+") && getElIndex(e) == Integer.parseInt(p.getValue())) {
                        return e;
                    } else if (p.getValue().endsWith("()")
                            && (Boolean) callFilterFunc(p.getValue().substring(0, p.getValue().length() - 2), e)) {
                        return e;
                    } else if (p.getValue().startsWith("@")
                            && e.hasAttr(StringUtils.substringAfter(p.getValue(), "@"))) {
                        return e;
                    }
                    // todo p.value ~= contains(./@href,'renren.com')
                } else {
                    if (p.getLeft().matches("[^/]+\\(\\)")) {
                        Object filterRes = p.getOpEm().excute(
                                callFilterFunc(p.getLeft().substring(0, p.getLeft().length() - 2), e).toString(),
                                p.getRight());
                        if (filterRes instanceof Boolean && (Boolean) filterRes) {
                            return e;
                        } else if (filterRes instanceof Integer
                                && e.siblingIndex() == Integer.parseInt(filterRes.toString())) {
                            return e;
                        }
                    } else if (p.getLeft().startsWith("@")) {
                        String lValue = e.attr(p.getLeft().substring(1));
                        Object filterRes = p.getOpEm().excute(lValue, p.getRight());
                        if ((Boolean) filterRes) {
                            return e;
                        }
                    } else {
                        // 操作符左边不是函数、属性默认就是xpath表达式了
                        List<Element> eltmp = new LinkedList<Element>();
                        eltmp.add(e);
                        List<JXNode> rstmp = XpathEvaluator.compile(p.getLeft()).xpathSelect(new Elements(eltmp));
                        if ((Boolean) p.getOpEm().excute(StringUtils.join(rstmp, ""), p.getRight())) {
                            return e;
                        }
                    }
                }
            } else {
                return e;
            }
        }
        return null;
    }

    /**
     * 调用轴选择器
     *
     * @param axis
     * @param e
     * @return
     * @throws NoSuchAxisException
     */
    public Elements getAxisScopeEls(String axis, Element e) {
        // try {
        // String functionName = CommonUtil.getJMethodNameFromStr(axis);
        // Method axisSelector = axisFuncs.get(renderFuncKey(functionName, e.getClass()));
        // return (Elements) axisSelector.invoke(FunctionEnv.getInstance().getAxisSelector(), e);
        // } catch (Exception e1) {
        // throw new NoSuchAxisException("this axis is not supported,plase use other instead of '" + axis + "'");
        // }
        return FunctionEnv.getAxisFunction(axis).call(e);
    }

    /**
     * 调用xpath主干上的函数
     *
     * @param funcname
     * @param context
     * @return
     * @deprecated
     */
    public Object callFunc(String funcname, Elements context) {

        // try {
        // Method function = emFuncs.get(renderFuncKey(funcname, context.getClass()));
        // return function.invoke(FunctionEnv.getInstance().getFunctions(), context);
        // } catch (Exception e) {
        // throw new NoSuchFunctionException("This function is not supported");
        // }
        return FunctionEnv.getSelectFunction(funcname).call(context);
    }

    /**
     * 调用谓语中函数
     *
     * @param funcname
     * @param el
     * @return
     */
    public Object callFilterFunc(String funcname, Element el) {
        // try {
        // Method function = emFuncs.get(renderFuncKey(funcname, el.getClass()));
        // return function.invoke(FunctionEnv.getInstance().getFunctions(), el);
        // } catch (Exception e) {
        // throw new NoSuchFunctionException("This function is not supported");
        // }
        return FunctionEnv.getFilterFunction(funcname).call(el);
    }

    public int getElIndex(Element e) {
        if (e != null) {
            return CommonUtil.getElIndexInSameTags(e);
        }
        return 1;
    }

    private String renderFuncKey(String funcName, Class... params) {
        return funcName + "|" + StringUtils.join(params, ",");
    }

}
