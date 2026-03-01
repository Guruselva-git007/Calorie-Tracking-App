import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.calorietracker.app',
  appName: 'Calorie Tracker',
  webDir: 'build',
  server: {
    cleartext: true
  }
};

export default config;
