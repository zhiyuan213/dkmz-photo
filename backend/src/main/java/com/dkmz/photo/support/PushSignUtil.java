package com.dkmz.photo.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 微信「消息推送」通用工具（与 exam-countdown 同源，踩坑点已固化）。
 *
 * 签名规则：signature = sha1(sort(token, timestamp, nonce))，三个字符串按字典序排序后拼接再做 SHA1。
 *
 * XML 解析：微信推送格式可选 JSON 或 XML，这里把 XML 转成等价 JsonNode，
 * 让发货逻辑只处理一种数据结构。「字符串字段」用有无元素子节点判断，
 * 因为微信 XML 的字符串字段是 CDATA（CDATA_SECTION_NODE 不是 TEXT_NODE）—— 曾因此解析失败。
 */
public final class PushSignUtil {

    private PushSignUtil() {
    }

    /** 校验微信推送签名；signature 为空视为不通过 */
    public static boolean check(String token, String timestamp, String nonce, String signature) {
        if (token == null || token.isBlank()
                || timestamp == null || nonce == null || signature == null) {
            return false;
        }
        try {
            String[] arr = {token, timestamp, nonce};
            Arrays.sort(arr);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest((arr[0] + arr[1] + arr[2]).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().equalsIgnoreCase(signature.trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** XML 报文 -> JsonNode（嵌套节点转对象，同名子节点取第一个） */
    public static JsonNode xmlToJson(ObjectMapper om, String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        Document doc;
        try (ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            doc = factory.newDocumentBuilder().parse(in);
        }
        ObjectNode root = om.createObjectNode();
        Element rootEl = doc.getDocumentElement();
        NodeList children = rootEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) child;
            if (!hasElementChild(el)) {
                root.put(el.getNodeName(), el.getTextContent());
            } else {
                ObjectNode nested = om.createObjectNode();
                NodeList sub = el.getChildNodes();
                for (int j = 0; j < sub.getLength(); j++) {
                    Node s = sub.item(j);
                    if (s.getNodeType() == Node.ELEMENT_NODE) {
                        nested.put(s.getNodeName(), s.getTextContent());
                    }
                }
                root.set(el.getNodeName(), nested);
            }
        }
        coerceNumbers(root, "CreateTime", "Env", "RetryTimes");
        JsonNode goods = root.path("GoodsInfo");
        if (goods.isObject()) {
            coerceNumbers((ObjectNode) goods, "Quantity", "OrigPrice", "ActualPrice");
        }
        JsonNode pay = root.path("WeChatPayInfo");
        if (pay.isObject()) {
            coerceNumbers((ObjectNode) pay, "PaidTime");
        }
        return root;
    }

    private static boolean hasElementChild(Element el) {
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    private static void coerceNumbers(ObjectNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.isTextual()) {
                try {
                    node.put(f, Long.parseLong(v.asText().trim()));
                } catch (NumberFormatException ignored) {
                    // 非数字保持文本
                }
            }
        }
    }
}
