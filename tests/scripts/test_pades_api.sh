#!/bin/bash
#
# PAdES API Test Script
# Tests the PAdES signing endpoints of the document-signer API
#
# Usage: ./test_pades_api.sh [--start-server]
#
# Options:
#   --start-server    Start the API server before running tests
#

set -e

# Configuration
API_BASE="http://localhost:8080/api/v1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PDF_FILE="$SCRIPT_DIR/pdf1.pdf"
CERT_FILE="$SCRIPT_DIR/BP2025.pfx"
CERT_PASSWORD="klobo123$"
OUTPUT_DIR="$SCRIPT_DIR/output"
MAX_WAIT=30

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counters
TESTS_PASSED=0
TESTS_FAILED=0

print_header() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  PAdES API Test Suite${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

print_test() {
    echo -e "${YELLOW}[TEST]${NC} $1"
}

print_pass() {
    echo -e "${GREEN}[PASS]${NC} $1"
    ((TESTS_PASSED++))
}

print_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    ((TESTS_FAILED++))
}

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

# Check if required files exist
check_prerequisites() {
    print_info "Checking prerequisites..."

    if [ ! -f "$PDF_FILE" ]; then
        echo -e "${RED}ERROR: PDF file not found: $PDF_FILE${NC}"
        exit 1
    fi

    if [ ! -f "$CERT_FILE" ]; then
        echo -e "${RED}ERROR: Certificate file not found: $CERT_FILE${NC}"
        exit 1
    fi

    if ! command -v curl &> /dev/null; then
        echo -e "${RED}ERROR: curl is required but not installed${NC}"
        exit 1
    fi

    if ! command -v jq &> /dev/null; then
        echo -e "${YELLOW}WARNING: jq not installed, JSON output will not be formatted${NC}"
    fi

    # Create output directory
    mkdir -p "$OUTPUT_DIR"

    print_info "Prerequisites OK"
}

# Check if API server is running
check_server() {
    print_info "Checking if API server is running..."

    if curl -s --connect-timeout 2 "$API_BASE/health" > /dev/null 2>&1; then
        print_info "API server is running"
        return 0
    else
        return 1
    fi
}

# Wait for server to be ready
wait_for_server() {
    print_info "Waiting for API server to be ready (max ${MAX_WAIT}s)..."

    for i in $(seq 1 $MAX_WAIT); do
        if curl -s --connect-timeout 2 "$API_BASE/health" > /dev/null 2>&1; then
            print_info "API server is ready!"
            return 0
        fi
        echo -n "."
        sleep 1
    done

    echo ""
    echo -e "${RED}ERROR: API server did not become ready within ${MAX_WAIT} seconds${NC}"
    return 1
}

# Start the API server
start_server() {
    print_info "Starting API server..."

    cd "$SCRIPT_DIR/.."

    # Check if already running
    if check_server; then
        print_info "Server is already running"
        return 0
    fi

    # Start server in background
    nohup java -jar target/ProcStudioSigner2.jar --api > "$OUTPUT_DIR/server.log" 2>&1 &
    SERVER_PID=$!
    echo $SERVER_PID > "$OUTPUT_DIR/server.pid"

    print_info "Server started with PID $SERVER_PID"

    # Wait for server to be ready
    wait_for_server
}

# Stop the API server
stop_server() {
    if [ -f "$OUTPUT_DIR/server.pid" ]; then
        PID=$(cat "$OUTPUT_DIR/server.pid")
        if kill -0 $PID 2>/dev/null; then
            print_info "Stopping API server (PID $PID)..."
            kill $PID
            rm -f "$OUTPUT_DIR/server.pid"
        fi
    fi
}

# Test: Health endpoint
test_health() {
    print_test "Health endpoint"

    RESPONSE=$(curl -s "$API_BASE/health")

    if echo "$RESPONSE" | grep -q '"status":"ok"'; then
        print_pass "Health check returned OK"
    else
        print_fail "Health check failed: $RESPONSE"
    fi
}

# Test: Sign PDF with PAdES (invisible signature)
test_sign_pdf_invisible() {
    print_test "Sign PDF with PAdES (invisible signature)"

    OUTPUT_FILE="$OUTPUT_DIR/signed_invisible.pdf"

    HTTP_CODE=$(curl -s -w "%{http_code}" -o "$OUTPUT_FILE" \
        -F "document=@$PDF_FILE" \
        -F "certificate=@$CERT_FILE" \
        -F "password=$CERT_PASSWORD" \
        "$API_BASE/sign/pdf")

    if [ "$HTTP_CODE" = "200" ] && [ -f "$OUTPUT_FILE" ] && [ -s "$OUTPUT_FILE" ]; then
        SIZE=$(stat -c%s "$OUTPUT_FILE" 2>/dev/null || stat -f%z "$OUTPUT_FILE" 2>/dev/null)
        print_pass "Signed PDF created: $OUTPUT_FILE ($SIZE bytes)"
    else
        print_fail "Failed to sign PDF (HTTP $HTTP_CODE)"
    fi
}

# Test: Sign PDF with PAdES and metadata
test_sign_pdf_metadata() {
    print_test "Sign PDF with PAdES and metadata"

    OUTPUT_FILE="$OUTPUT_DIR/signed_metadata.pdf"

    HTTP_CODE=$(curl -s -w "%{http_code}" -o "$OUTPUT_FILE" \
        -F "document=@$PDF_FILE" \
        -F "certificate=@$CERT_FILE" \
        -F "password=$CERT_PASSWORD" \
        -F "reason=Acordo comercial" \
        -F "location=Sao Paulo, Brasil" \
        "$API_BASE/sign/pdf")

    if [ "$HTTP_CODE" = "200" ] && [ -f "$OUTPUT_FILE" ] && [ -s "$OUTPUT_FILE" ]; then
        SIZE=$(stat -c%s "$OUTPUT_FILE" 2>/dev/null || stat -f%z "$OUTPUT_FILE" 2>/dev/null)
        print_pass "Signed PDF with metadata created: $OUTPUT_FILE ($SIZE bytes)"
    else
        print_fail "Failed to sign PDF with metadata (HTTP $HTTP_CODE)"
    fi
}

# Test: Sign PDF with visible signature
test_sign_pdf_visible() {
    print_test "Sign PDF with visible signature"

    OUTPUT_FILE="$OUTPUT_DIR/signed_visible.pdf"

    HTTP_CODE=$(curl -s -w "%{http_code}" -o "$OUTPUT_FILE" \
        -F "document=@$PDF_FILE" \
        -F "certificate=@$CERT_FILE" \
        -F "password=$CERT_PASSWORD" \
        -F "visible=true" \
        -F "page=1" \
        -F "position=bottom-right" \
        "$API_BASE/sign/pdf")

    if [ "$HTTP_CODE" = "200" ] && [ -f "$OUTPUT_FILE" ] && [ -s "$OUTPUT_FILE" ]; then
        SIZE=$(stat -c%s "$OUTPUT_FILE" 2>/dev/null || stat -f%z "$OUTPUT_FILE" 2>/dev/null)
        print_pass "Signed PDF with visible signature created: $OUTPUT_FILE ($SIZE bytes)"
    else
        print_fail "Failed to sign PDF with visible signature (HTTP $HTTP_CODE)"
    fi
}

# Test: Sign PDF and return JSON
test_sign_pdf_json() {
    print_test "Sign PDF and return JSON (base64)"

    RESPONSE=$(curl -s \
        -F "document=@$PDF_FILE" \
        -F "certificate=@$CERT_FILE" \
        -F "password=$CERT_PASSWORD" \
        "$API_BASE/sign/pdf/json")

    if echo "$RESPONSE" | grep -q '"success":true'; then
        print_pass "JSON response with base64 received"
    else
        print_fail "Failed to get JSON response: $RESPONSE"
    fi
}

# Test: Verify signed PDF
test_verify_pdf() {
    print_test "Verify signed PDF signature"

    SIGNED_FILE="$OUTPUT_DIR/signed_invisible.pdf"

    if [ ! -f "$SIGNED_FILE" ]; then
        print_fail "Signed file not found for verification"
        return
    fi

    RESPONSE=$(curl -s \
        -F "document=@$SIGNED_FILE" \
        "$API_BASE/verify/pdf")

    if echo "$RESPONSE" | grep -q '"valid":true'; then
        print_pass "Signature verified as VALID"
    else
        print_fail "Signature verification failed: $RESPONSE"
    fi
}

# Test: Sign and verify in one call
test_sign_and_verify() {
    print_test "Sign PDF and verify in one call"

    RESPONSE=$(curl -s \
        -F "document=@$PDF_FILE" \
        -F "certificate=@$CERT_FILE" \
        -F "password=$CERT_PASSWORD" \
        "$API_BASE/sign/pdf/verified")

    if echo "$RESPONSE" | grep -q '"success":true' && echo "$RESPONSE" | grep -q '"valid":true'; then
        print_pass "Sign and verify completed successfully"
    else
        print_fail "Sign and verify failed: $RESPONSE"
    fi
}

# Test: Invalid password
test_invalid_password() {
    print_test "Reject invalid certificate password"

    RESPONSE=$(curl -s \
        -F "document=@$PDF_FILE" \
        -F "certificate=@$CERT_FILE" \
        -F "password=wrongpassword" \
        "$API_BASE/sign/pdf/json")

    if echo "$RESPONSE" | grep -qi "password\|invalid"; then
        print_pass "Invalid password correctly rejected"
    else
        print_fail "Invalid password was not rejected properly"
    fi
}

# Print summary
print_summary() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  Test Summary${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
    echo -e "  ${GREEN}Passed: $TESTS_PASSED${NC}"
    echo -e "  ${RED}Failed: $TESTS_FAILED${NC}"
    echo ""

    if [ $TESTS_FAILED -eq 0 ]; then
        echo -e "${GREEN}All tests passed!${NC}"
        echo ""
        echo "Signed PDF files are in: $OUTPUT_DIR"
    else
        echo -e "${RED}Some tests failed.${NC}"
    fi
    echo ""
}

# Main
main() {
    print_header

    # Parse arguments
    START_SERVER=false
    for arg in "$@"; do
        case $arg in
            --start-server)
                START_SERVER=true
                ;;
        esac
    done

    # Check prerequisites
    check_prerequisites

    # Check/start server
    if ! check_server; then
        if [ "$START_SERVER" = true ]; then
            start_server
        else
            echo ""
            echo -e "${RED}ERROR: API server is not running!${NC}"
            echo ""
            echo "Please start the server first:"
            echo "  java -jar target/ProcStudioSigner2.jar --api"
            echo ""
            echo "Or run this script with --start-server:"
            echo "  ./test_pades_api.sh --start-server"
            echo ""
            exit 1
        fi
    fi

    echo ""
    echo -e "${BLUE}Running tests...${NC}"
    echo ""

    # Run tests
    test_health
    test_sign_pdf_invisible
    test_sign_pdf_metadata
    test_sign_pdf_visible
    test_sign_pdf_json
    test_verify_pdf
    test_sign_and_verify
    test_invalid_password

    # Print summary
    print_summary

    # Return exit code based on test results
    [ $TESTS_FAILED -eq 0 ]
}

# Run main
main "$@"
