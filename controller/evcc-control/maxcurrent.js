dcTargetKw = maxcurrent > 0
  ? Math.max(5, Math.min(35, Math.ceil(maxcurrent * Math.sqrt(3) * 400 / 1000)))
  : 0;
dcTargetKw;
