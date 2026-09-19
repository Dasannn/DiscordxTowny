package com.discordtowny.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimpleJsonTest {

    @Test
    @DisplayName("Parses standard GitHub release JSON snippet")
    void parsesGitHubReleaseSnippet() {
        String json = """
                {
                  "tag_name": "v1.10.0",
                  "name": "DiscordTowny 1.10.0",
                  "body": "Changelog:\\n- Added updater\\nSHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "draft": false,
                  "prerelease": false,
                  "assets": [
                    {
                      "name": "DiscordTowny-1.10.0.jar",
                      "browser_download_url": "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar",
                      "size": 15000000
                    }
                  ]
                }
                """;

        SimpleJson.JsonObject obj = SimpleJson.parseObject(json);
        assertEquals("v1.10.0", obj.getString("tag_name"));
        assertEquals("DiscordTowny 1.10.0", obj.getString("name"));
        assertTrue(obj.getString("body").contains("Changelog:"));
        assertEquals(Boolean.FALSE, obj.getBoolean("draft", null));
        assertEquals(Boolean.FALSE, obj.getBoolean("prerelease", null));

        SimpleJson.JsonArray assets = obj.getArray("assets");
        assertNotNull(assets);
        assertEquals(1, assets.size());

        SimpleJson.JsonObject asset = assets.getObject(0);
        assertNotNull(asset);
        assertEquals("DiscordTowny-1.10.0.jar", asset.getString("name"));
        assertEquals("https://github.com/Dasannn/DiscordxTowny/releases/download/v1.10.0/DiscordTowny-1.10.0.jar", asset.getString("browser_download_url"));
        assertEquals(15000000L, asset.getLong("size", 0L));
    }

    @Test
    @DisplayName("Handles escape characters in strings")
    void handlesEscapeCharacters() {
        String json = """
                {
                  "text": "Line 1\\nLine 2\\tTabbed \\"Quotes\\" \\\\ Backslash \\u0041"
                }
                """;

        SimpleJson.JsonObject obj = SimpleJson.parseObject(json);
        assertEquals("Line 1\nLine 2\tTabbed \"Quotes\" \\ Backslash A", obj.getString("text"));
    }

    @Test
    @DisplayName("Rejects malformed JSON syntax with IllegalArgumentException")
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> SimpleJson.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SimpleJson.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> SimpleJson.parse("{ unquoted_key: 123 }"));
        assertThrows(IllegalArgumentException.class, () -> SimpleJson.parse("{\"unclosed\": \"str"));
        assertThrows(IllegalArgumentException.class, () -> SimpleJson.parse("{\"trailing\": 123}, extra"));
        assertThrows(IllegalArgumentException.class, () -> SimpleJson.parse("<html>Bad Gateway</html>"));
    }

    @Test
    @DisplayName("Throws when parseObject is called on non-object value")
    void throwsWhenExpectedObjectIsArray() {
        assertThrows(IllegalArgumentException.class, () -> SimpleJson.parseObject("[1, 2, 3]"));
    }

    @Test
    @DisplayName("Rejects deeply nested arrays and objects exceeding depth limit")
    void rejectsDeeplyNestedArraysAndObjects() {
        StringBuilder nestedObj = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            nestedObj.append("{\"a\":");
        }
        nestedObj.append("1");
        for (int i = 0; i < 70; i++) {
            nestedObj.append("}");
        }
        assertThrows(IllegalArgumentException.class, () -> SimpleJson.parse(nestedObj.toString()));

        StringBuilder nestedArr = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            nestedArr.append("[");
        }
        nestedArr.append("1");
        for (int i = 0; i < 70; i++) {
            nestedArr.append("]");
        }
        assertThrows(IllegalArgumentException.class, () -> SimpleJson.parse(nestedArr.toString()));
    }
}
