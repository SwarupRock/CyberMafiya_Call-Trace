export const transcriptLines = [
  {
    id: 't1',
    time: '00:03',
    source: 'caller',
    text: 'This is the bank security desk. Your wallet is under urgent review.',
  },
  {
    id: 't2',
    time: '00:07',
    source: 'shield',
    text: 'Authority claim detected. Caller identity confidence is below baseline.',
  },
  {
    id: 't3',
    time: '00:12',
    source: 'caller',
    text: 'Verify the OTP now or we will freeze the account.',
  },
  {
    id: 't4',
    time: '00:16',
    source: 'shield',
    text: 'Credential extraction pattern matched. Honeypot response prepared.',
  },
  {
    id: 't5',
    time: '00:22',
    source: 'caller',
    text: 'Do not disconnect. This is linked to an arrest warrant.',
  },
  {
    id: 't6',
    time: '00:28',
    source: 'shield',
    text: 'Escalation script confirmed. Number queued for block and report.',
  },
]

export const intercepts = [
  { id: 'i1', number: '+91 88201 44509', vector: 'OTP extraction', score: '99%', severity: 'critical', time: '2m ago' },
  { id: 'i2', number: '+1 415 090 7781', vector: 'Refund lure', score: '84%', severity: 'high', time: '18m ago' },
  { id: 'i3', number: '+44 7700 900531', vector: 'Bank freeze script', score: '91%', severity: 'critical', time: '41m ago' },
]
