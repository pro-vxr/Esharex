import fs from 'node:fs';
import path from 'node:path';

const gradleFile = path.resolve('android/app/build.gradle');
if (!fs.existsSync(gradleFile)) throw new Error(`Android Gradle file not found: ${gradleFile}`);

const versionCode = Number(process.env.ANDROID_VERSION_CODE);
if (!Number.isSafeInteger(versionCode) || versionCode < 1) {
  throw new Error('ANDROID_VERSION_CODE must be a positive integer.');
}
for (const key of ['ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD']) {
  if (!process.env[key]) throw new Error(`${key} is required to sign the release APK.`);
}

let gradle = fs.readFileSync(gradleFile, 'utf8');
if (!gradle.includes('buildTypes {') || !/versionCode\s+\d+/.test(gradle)) {
  throw new Error('Unexpected Capacitor build.gradle format; refusing to modify it.');
}

if (!gradle.includes('signingConfigs {')) {
  const signingConfigs = `    signingConfigs {
        release {
            storeFile file("esharex-release.jks")
            storePassword System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias System.getenv("ANDROID_KEY_ALIAS")
            keyPassword System.getenv("ANDROID_KEY_PASSWORD")
        }
    }`;
  gradle = gradle.replace(/(^\s*)buildTypes\s*\{/m, `${signingConfigs}\n\n    buildTypes {`);
  if (!gradle.includes('signingConfigs {')) throw new Error('Could not add release signing configuration to build.gradle.');
}

if (!/release\s*\{\s*signingConfig\s+signingConfigs\.release/.test(gradle)) {
  const releaseBlock = /(buildTypes\s*\{\s*release\s*\{)/;
  if (!releaseBlock.test(gradle)) throw new Error('Could not find the release build type in build.gradle.');
  gradle = gradle.replace(releaseBlock, '$1\n            signingConfig signingConfigs.release');
}

gradle = gradle.replace(/versionCode\s+\d+/, `versionCode ${versionCode}`);
fs.writeFileSync(gradleFile, gradle);
console.log(`Configured signed release APK with versionCode ${versionCode}.`);
