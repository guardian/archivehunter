import React, {useContext} from "react";
import {UserContext} from "./Context/UserContext";
import {Redirect} from "react-router-dom";
import LoginComponent from "./LoginComponent";

const RootComponent:React.FC  = () => {
    const userContext = useContext(UserContext);

    return (
        userContext.profile ? <Redirect to="/search"/> : <LoginComponent/>
    )
}

export default RootComponent;