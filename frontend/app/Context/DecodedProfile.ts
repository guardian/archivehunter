import {fromUnixTime,addMinutes} from 'date-fns';

interface JwtDataShape {
  aud: string;
  iss: string;
  iat: number;
  iat_moment?: Date;
  exp: number;
  exp_moment?: Date;
  sub?: string;
  email?: string;
  first_name?: string;
  given_name?: string;
  family_name?: string;
  username?: string;
  preferred_username?: string;
  location?: string;
  job_title?: string;
  authmethod?: string;
  auth_time?: string;
  ver?: string;
  appid?: string;
}

function utcTime(from: number) {
  //see https://stackoverflow.com/a/61469549. `fromUnixTime` gives us a local time, but we want UTC.
  const date = fromUnixTime(from);
  return addMinutes(date, date.getTimezoneOffset());
}

function JwtData(jwtData: object) {
  return new Proxy(<JwtDataShape>jwtData, {
    get(target, prop) {
      switch (prop) {
        case "iat_moment":
          return utcTime(target.iat);
        case "exp_moment":
          return utcTime(target.exp);
        case "username":
          return target.preferred_username ?? target.username;
        case "first_name":
          return target.first_name ?? target.given_name;
        default:
          return (<any>target)[prop] ?? null;
      }
    },
  });
}

export type { JwtDataShape };
export { JwtData };
