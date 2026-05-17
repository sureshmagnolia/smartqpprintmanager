package com.printmanager.model;

import java.util.ArrayList;
import java.util.List;

public class SmartSplitRule {
    private boolean enabled = true;
    private String keyword = "Multiple Choice Questions for SDE";
    private String beforePrefix = "Main_";
    private String afterPrefix = "MCQ_";
    
    // Page count routing for 'Before' part
    private String beforeStyle1 = "Simplex";
    private String beforeStyle2 = "Duplex";
    private String beforeStyle3Plus = "Booklet";

    // Page count routing for 'After' part
    private String afterStyle1 = "Simplex";
    private String afterStyle2 = "Duplex";
    private String afterStyle3To4 = "Booklet";
    private boolean special5PageMode = true; // Remove keyword page, add overlay
    private String afterOverlayText = "MCQ";
    private String skipThresholds = "5,9";
    private boolean skipKeywordPage = true;
    private String afterStyle6Plus = "Booklet";

    public SmartSplitRule() {}

    // Getters and Setters
    public String getAfterOverlayText() { return afterOverlayText; }
    public void setAfterOverlayText(String afterOverlayText) { this.afterOverlayText = afterOverlayText; }
    public String getSkipThresholds() { return skipThresholds; }
    public void setSkipThresholds(String skipThresholds) { this.skipThresholds = skipThresholds; }
    public boolean isSkipKeywordPage() { return skipKeywordPage; }
    public void setSkipKeywordPage(boolean skipKeywordPage) { this.skipKeywordPage = skipKeywordPage; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public String getBeforePrefix() { return beforePrefix; }
    public void setBeforePrefix(String beforePrefix) { this.beforePrefix = beforePrefix; }
    public String getAfterPrefix() { return afterPrefix; }
    public void setAfterPrefix(String afterPrefix) { this.afterPrefix = afterPrefix; }
    public String getBeforeStyle1() { return beforeStyle1; }
    public void setBeforeStyle1(String beforeStyle1) { this.beforeStyle1 = beforeStyle1; }
    public String getBeforeStyle2() { return beforeStyle2; }
    public void setBeforeStyle2(String beforeStyle2) { this.beforeStyle2 = beforeStyle2; }
    public String getBeforeStyle3Plus() { return beforeStyle3Plus; }
    public void setBeforeStyle3Plus(String beforeStyle3Plus) { this.beforeStyle3Plus = beforeStyle3Plus; }
    public String getAfterStyle1() { return afterStyle1; }
    public void setAfterStyle1(String afterStyle1) { this.afterStyle1 = afterStyle1; }
    public String getAfterStyle2() { return afterStyle2; }
    public void setAfterStyle2(String afterStyle2) { this.afterStyle2 = afterStyle2; }
    public String getAfterStyle3To4() { return afterStyle3To4; }
    public void setAfterStyle3To4(String afterStyle3To4) { this.afterStyle3To4 = afterStyle3To4; }
    public boolean isSpecial5PageMode() { return special5PageMode; }
    public void setSpecial5PageMode(boolean special5PageMode) { this.special5PageMode = special5PageMode; }
    public String getAfterStyle6Plus() { return afterStyle6Plus; }
    public void setAfterStyle6Plus(String afterStyle6Plus) { this.afterStyle6Plus = afterStyle6Plus; }

    @Override
    public String toString() {
        return (enabled ? "[ON] " : "[OFF] ") + keyword;
    }
}
