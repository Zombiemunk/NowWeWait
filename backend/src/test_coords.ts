import { utmToLatLng } from './utils/coordinates.js';

const x = 543109.45;
const y = 4795286.69;

const result = utmToLatLng(x, y);
console.log('UTM coordinates:', x, y);
console.log('Resulting Lat:', result.lat);
console.log('Resulting Lng:', result.lng);
console.log('Expected Lat ~ 43.309179, Lng ~ -2.468431');
