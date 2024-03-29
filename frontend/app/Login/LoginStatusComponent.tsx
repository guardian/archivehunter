import React, {useContext, useState} from 'react';
import {Redirect} from "react-router-dom";
import axios from 'axios';
import {createStyles, makeStyles, withStyles} from "@material-ui/core";
import {UserContext} from "../Context/UserContext";

interface LoginStatusComponentProps {
    userLoggedOutCb: ()=>void;
}

interface LoginStatusComponentState {
    opened?: boolean;
    redirecting?: boolean;
}

const useStyles = makeStyles({
    loginInfoContainer: {
        float: "right",
        position: "absolute",
        top: 0,
        right: "1em",
        height: "max-content",
        padding: "0.4em",
        overflow: "hidden",
        cursor: "pointer"
    },
    loginUserAvatar: {
        height: "40px",
        borderRadius: "8px",
        verticalAlign: "middle",
    },
    loginInfoText: {
        color: "#AAAAAA",
        margin: "0.5em"
    },
    discreetList: {
        textAlign: "right",
        listStyle: "none"
    }
});

const LoginStatusComponent:React.FC<LoginStatusComponentProps> = (props) => {
    const classes = useStyles();
    const [opened, setOpened] = useState(false);
    const [redirecting, setRedirecting] = useState(false);

    const userContext = useContext(UserContext);

    const trackedOut = () => {
        window.setTimeout(()=>setOpened(false), 200);
    }

    const doLogout = async () => {
        window.location.href = "/logout";
    }

    if(redirecting) return <Redirect to="/"/>;

    const displayName = ()=>{
        if(userContext.profile && userContext.profile?.firstName!="" && userContext.profile?.lastName!="") {
            return `${userContext.profile?.firstName} ${userContext.profile?.lastName}`;
        } else if(userContext.profile) {
            return userContext.profile.email;
        } else {
            return "unknown user";
        }
    }

    return <div className={classes.loginInfoContainer}
                onMouseEnter={()=>setOpened(true)}
                onMouseLeave={trackedOut}>
        {
            userContext.profile ?
                <span className={classes.loginInfoText}>{displayName()}</span> :
                <span className={classes.loginInfoText}>Not logged in</span>
        }
        {
            userContext.profile ?
                <img className={classes.loginUserAvatar} src={userContext.profile.avatarUrl}/> :
                undefined
        }
        <div style={{width: "100%", display: opened ? "block" : "none"}}>
            <ul className={classes.discreetList}>
                <li onClick={doLogout}>Log out</li>
            </ul>
        </div>
    </div>

}

export default LoginStatusComponent;