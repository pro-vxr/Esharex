import { Capacitor } from '@capacitor/core';
import { Nodejs } from '@capawesome/capacitor-nodejs';

async function startAndroidServer() {
  if (!Capacitor.isNativePlatform()) return;

  const openLocalServer = () => {
    if (window.location.hostname !== '127.0.0.1') {
      window.location.replace('http://127.0.0.1:8080/');
    }
  };

  try {
    await Nodejs.addListener('ready', openLocalServer);
    const { ready } = await Nodejs.isReady();
    if (ready) openLocalServer();
  } catch (error) {
    console.error('eShareX Node.js runtime startup error:', error);
  }
}

startAndroidServer();
