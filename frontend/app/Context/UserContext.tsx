import React from "react";
import {UserDetails} from "../types";

interface UserContext {
  profile?: UserDetails;
  updateProfile: (newValue?: UserDetails) => void;
}

const UserContext = React.createContext<UserContext>({
  profile: undefined,
  updateProfile: (newValue) => {},
});

export const UserContextProvider = UserContext.Provider;
export { UserContext };
