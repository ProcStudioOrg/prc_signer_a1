package com.example.documentsigner.pades.dto;

/**
 * Configuration for visual signature appearance in PAdES.
 */
public class VisualSignatureConfig {
    private boolean enabled;
    private int page = 1;
    private SignaturePosition position = SignaturePosition.BOTTOM_RIGHT;
    private Float x;
    private Float y;
    private float width = 240;
    private float height = 102;

    public VisualSignatureConfig() {
    }

    public VisualSignatureConfig(boolean enabled, int page, SignaturePosition position,
                                  Float x, Float y, float width, float height) {
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

    public Float getX() {
        return x;
    }

    public void setX(Float x) {
        this.x = x;
    }

    public Float getY() {
        return y;
    }

    public void setY(Float y) {
        this.y = y;
    }

    public float getWidth() {
        return width;
    }

    public void setWidth(float width) {
        this.width = width;
    }

    public float getHeight() {
        return height;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean enabled = false;
        private int page = 1;
        private SignaturePosition position = SignaturePosition.BOTTOM_RIGHT;
        private Float x;
        private Float y;
        private float width = 240;
        private float height = 102;

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

        public Builder x(Float x) {
            this.x = x;
            return this;
        }

        public Builder y(Float y) {
            this.y = y;
            return this;
        }

        public Builder width(float width) {
            this.width = width;
            return this;
        }

        public Builder height(float height) {
            this.height = height;
            return this;
        }

        public VisualSignatureConfig build() {
            return new VisualSignatureConfig(enabled, page, position, x, y, width, height);
        }
    }
}
