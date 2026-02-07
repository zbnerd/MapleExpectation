# Code Formatting Standards

## Overview

MapleExpectation uses **Spotless** with **Google Java Format** to maintain consistent code style across the codebase. This document documents the formatting standards and configuration.

## Tooling

- **Plugin**: Spotless (Gradle plugin)
- **Version**: 6.25.0
- **Formatter**: Google Java Format
- **Build Integration**: Automated via Gradle tasks

## Spotless Configuration

### Current Configuration (build.gradle)

```gradle
spotless {
    java {
        googleJavaFormat()
        formatAnnotations()
        importOrder()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
```

### Configuration Details

| Rule | Purpose |
|------|---------|
| `googleJavaFormat()` | Applies Google Java Format style (2-space indentation) |
| `formatAnnotations()` | Formats annotations consistently |
| `importOrder()` | Sorts imports according to Google style |
| `removeUnusedImports()` | Removes unused import statements |
| `trimTrailingWhitespace()` | Removes trailing spaces from lines |
| `endWithNewline()` | Ensures files end with newline |

## Google Java Format Style Guidelines

### Key Formatting Rules

#### 1. Indentation
- **2 spaces** (NO tabs)
- Consistent with Google Java Format standard

#### 2. Javadoc Comments
- Block Javadoc tags (`<p>`, `<b>`, etc.) should be on the same line as content
- Avoid unnecessary line breaks in Javadoc
- Compact multi-sentence Javadoc where appropriate

**Before:**
```java
/**
 * <p>
 * <b>활성화된 기능:</b>
 * <ul>
 *   <li>Item 1</li>
 * </ul>
 */
```

**After:**
```java
/**
 *
 * <p><b>활성화된 기능:</b>
 *
 * <ul>
 *   <li>Item 1
 * </ul>
 */
```

#### 3. Import Order
Google Java Format organizes imports in this order:

1. Static imports
2. `java.*` imports
3. `javax.*` imports
4. Third-party imports (sorted alphabetically)
5. Project-specific imports (sorted alphabetically)

#### 4. Line Length
- **Maximum**: 100 characters (Google Java Format default)
- Formatter will automatically wrap long lines

#### 5. Whitespace
- No trailing whitespace
- Files must end with newline
- Single blank line between methods/classes (unless grouped)

## Gradle Tasks

### Check Formatting
```bash
./gradlew spotlessCheck
```
- Verifies all Java files conform to formatting standards
- Fails if any violations exist
- **Used in CI pipeline**

### Apply Formatting
```bash
./gradlew spotlessApply
```
- Automatically reformats all Java files
- Safe to run anytime (idempotent)
- **Run before committing code**

### Check Specific File
```bash
./gradlew spotlessCheck --info
```
Shows detailed information about formatting violations.

## CI/CD Integration

Spotless is integrated into the CI pipeline:
- **Workflow**: `.github/workflows/ci.yml`
- **Stage**: Code quality checks (before tests)
- **Action**: Fails build if formatting violations detected
- **Remediation**: Developer must run `./gradlew spotlessApply` and push fix

## Phase 1 Baseline Results

### Initial State
- **Total files with violations**: 592 Java files
- **Violation types**: Inconsistent indentation, Javadoc formatting, import ordering, trailing whitespace

### After Applying Spotless
- **Files reformatted**: 592 files
- **Status**: All files now conform to Google Java Format
- **Verification**: `./gradlew spotlessCheck` passes

### Sample Changes

#### Javadoc Formatting
- Compact multi-line Javadoc into fewer lines
- Align `<p>` tags with content on same line
- Remove unnecessary vertical spacing in Javadoc blocks

#### Indentation
- All files use 2-space indentation consistently
- Tab characters removed

#### Import Cleanup
- Unused imports automatically removed
- Imports sorted according to Google style guidelines

## Development Workflow

### Before Committing
1. **Run formatting check**:
   ```bash
   ./gradlew spotlessCheck
   ```

2. **If violations found**, apply fixes:
   ```bash
   ./gradlew spotlessApply
   ```

3. **Review changes**:
   ```bash
   git diff
   ```

4. **Commit and push**:
   ```bash
   git add .
   git commit -m "style: Apply Spotless formatting"
   git push
   ```

### Pre-commit Hook (Recommended)
Add to `.git/hooks/pre-commit`:
```bash
#!/bin/bash
./gradlew spotlessCheck || {
    echo "Code formatting violations detected!"
    echo "Run: ./gradlew spotlessApply"
    exit 1
}
```

## Troubleshooting

### Issue: Spotless check fails in CI but passes locally
**Cause**: Different line endings (CRLF vs LF)
**Fix**: Configure Git to use LF:
```bash
git config --global core.autocrlf input
```

### Issue: Formatter changes code logic
**Cause**: Extremely rare with Google Java Format
**Action**: Report to team, review the specific file manually

### Issue: Want to exclude specific file
**Solution**: Add to Spotless configuration:
```gradle
spotless {
    java {
        googleJavaFormat()
        targetExclude '**/generated/**', '**/build/**'
    }
}
```

## References

- [Google Java Format Guide](https://google.github.io/styleguide/javaguide.html)
- [Spotless Documentation](https://github.com/diffplug/spotless)
- [Gradle Spotless Plugin](https://plugins.gradle.org/plugin/com.diffplug.spotless)

## Version History

| Date | Change | Author |
|------|--------|--------|
| 2025-02-07 | Initial Spotless setup with Google Java Format | Claude Code |
