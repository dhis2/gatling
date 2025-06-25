#!/bin/bash
# Build script for glog - Gatling Log Parser CLI

set -e

echo "Building glog - Gatling Log Parser CLI"
echo "======================================"

# Build JVM executable
echo ""
echo "Building JVM executable..."
sbt "project gatling-log-parser-cli" stage

echo ""
echo "✓ JVM executable built successfully!"
echo "  Location: ./gatling-log-parser-cli/target/universal/stage/bin/glog"
echo "  Usage: ./gatling-log-parser-cli/target/universal/stage/bin/glog --help"

# Create a symlink in project root for easier access
if [ -L "./glog" ]; then
    rm ./glog
fi
ln -s gatling-log-parser-cli/target/universal/stage/bin/glog ./glog

echo ""
echo "✓ Created symlink: ./glog -> gatling-log-parser-cli/target/universal/stage/bin/glog"

echo ""
echo "Quick test:"
echo "  ./glog --help"

# Create distribution package
echo ""
echo "Creating distribution package..."
sbt "project gatling-log-parser-cli" Universal/packageBin

DIST_FILE=$(find gatling-log-parser-cli/target/universal -name "glog.zip" | head -1)
if [ -n "$DIST_FILE" ]; then
    echo "✓ Distribution package created: $DIST_FILE"
    echo "  This package contains the executable and all dependencies."
    echo "  Extract and run: bin/glog"
else
    echo "⚠️  Distribution package not found"
fi

echo ""
echo "======================================"
echo "glog build completed successfully! 🎉"
echo "======================================"