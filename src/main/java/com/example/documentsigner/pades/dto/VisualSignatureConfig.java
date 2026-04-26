package com.example.documentsigner.pades.dto;

/**
 * Configuration for visual signature appearance in PAdES.
 */
public class VisualSignatureConfig {
    private boolean enabled;
    private int page = 1;
    private SignaturePosition position = SignaturePosition.BOTTOM_RIGHT;
    private Integer x;
    private Integer y;
    private int width = 240;
    private int height = 102;

    public VisualSignatureConfig() {
    }

    public VisualSignatureConfig(boolean enabled, int page, SignaturePosition position,
                                  Integer x, Integer y, int width, int height) {
        this.enabled = enabled;
        this.page = page;
        this.position = position;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public SignaturePosition getPosition() {
        return position;
    }

    public void setPosition(SignaturePosition position) {
        this.position = position;
    }

    public Integer getX() {
        return x;
    }

    public void setX(Integer x) {
        this.x = x;
    }

    public Integer getY() {
        return y;
    }

    public void setY(Integer y) {
        this.y = y;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean enabled = false;
        private int page = 1;
        private SignaturePosition position = SignaturePosition.BOTTOM_RIGHT;
        private Integer x;
        private Integer y;
        private int width = 240;
        private int height = 102;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder page(int page) {
            this.page = page;
            return this;
        }

        public Builder position(SignaturePosition position) {
            this.position = position;
            return this;
        }

        public Builder x(Integer x) {
            this.x = x;
            return this;
        }

        public Builder y(Integer y) {
            this.y = y;
            return this;
        }

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder height(int height) {
            this.height = height;
            return this;
        }

        public VisualSignatureConfig build() {
            return new VisualSignatureConfig(enabled, page, position, x, y, width, height);
        }
    }
}
