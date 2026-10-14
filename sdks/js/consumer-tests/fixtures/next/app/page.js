'use client';

import { useEffect } from 'react';
import { installProbe } from './probe.js';

export default function Page() {
  useEffect(() => installProbe(), []);

  return null;
}
