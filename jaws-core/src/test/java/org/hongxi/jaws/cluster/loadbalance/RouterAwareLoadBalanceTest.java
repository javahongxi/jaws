package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.cluster.router.TagRouter;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 RouterChain 的过滤结果真正参与负载均衡选择（回归测试）
 */
class RouterAwareLoadBalanceTest {

    @Test
    void selectShouldOnlyChooseRoutedReferences() {
        RandomLoadBalance<String> lb = new RandomLoadBalance<>();
        lb.addRouter(new TagRouter<>());
        lb.onRefresh(createTaggedRefs());

        Request taggedRequest = mockRequest("gray");
        for (int i = 0; i < 200; i++) {
            Reference<String> selected = lb.select(taggedRequest);
            assertEquals("gray", ((TestReference) selected).getName(),
                    "带 tag 的请求只能选中匹配 tag 的 reference");
        }
    }

    @Test
    void selectCandidatesShouldOnlyContainRoutedReferences() {
        RandomLoadBalance<String> lb = new RandomLoadBalance<>();
        lb.addRouter(new TagRouter<>());
        lb.onRefresh(createTaggedRefs());

        Request taggedRequest = mockRequest("gray");
        List<Reference<String>> candidates = lb.selectCandidates(taggedRequest);
        assertFalse(candidates.isEmpty());
        for (Reference<String> candidate : candidates) {
            assertEquals("gray", ((TestReference) candidate).getName(),
                    "候选列表只能包含匹配 tag 的 reference");
        }
    }

    @Test
    void selectWithoutTagShouldSeeAllReferences() {
        RandomLoadBalance<String> lb = new RandomLoadBalance<>();
        lb.addRouter(new TagRouter<>());
        lb.onRefresh(createTaggedRefs());

        Request plainRequest = mockRequest(null);
        boolean hitStable = false;
        boolean hitGray = false;
        for (int i = 0; i < 500 && !(hitStable && hitGray); i++) {
            String name = ((TestReference) lb.select(plainRequest)).getName();
            hitStable |= name.startsWith("stable");
            hitGray |= "gray".equals(name);
        }
        assertTrue(hitStable && hitGray, "无 tag 请求应能命中所有 reference");
    }

    /**
     * two stable providers plus one gray provider
     */
    private List<Reference<String>> createTaggedRefs() {
        List<Reference<String>> refs = new ArrayList<>();
        refs.add(new TestReference("stable1", serviceUrl("127.0.0.1", null)));
        refs.add(new TestReference("stable2", serviceUrl("127.0.0.2", null)));
        refs.add(new TestReference("gray", serviceUrl("127.0.0.3", "gray")));
        return refs;
    }

    private URL serviceUrl(String host, String tag) {
        URL url = new URL("jaws", host, 8080, "testService");
        if (tag != null) {
            url.addParameter(UrlParam.Identity.TAG.getName(), tag);
        }
        return url;
    }

    private Request mockRequest(String tag) {
        Map<String, String> attachments = new HashMap<>();
        if (tag != null) {
            attachments.put(JawsConstants.TAG_ATTACHMENT, tag);
        }
        return new Request() {
            @Override public String getInterfaceName() { return "TestService"; }
            @Override public String getMethodName() { return "test"; }
            @Override public String getParamDesc() { return ""; }
            @Override public Object[] getArguments() { return null; }
            @Override public Map<String, String> getAttachments() { return attachments; }
            @Override public void setAttachment(String name, String value) {}
            @Override public long getRequestId() { return 0; }
            @Override public int getRetries() { return 0; }
            @Override public byte getSerializationNumber() { return 0; }
        };
    }
}
