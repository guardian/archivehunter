import React from "react";
import { JwtDataShape } from "./DecodedProfile";

interface UserContext {
  profile?: JwtDataShape;
  updateProfile: (newValue?: JwtDataShape) => void;
}

const UserContext = React.createContext<UserContext>({
  profile: undefined,
  updateProfile: (newValue) => {},
});

export const UserContextProvider = UserContext.Provider;
export { UserContext };
