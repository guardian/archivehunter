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
        right: 0,
        padding: "0.4em",
        overflow: "hidden",
        cursor: "pointer"
    },
    loginUserAvatar: {
        height: "24px",
        borderRadius: "24px",
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
        try {
            const response = await axios.post("/api/logout")
            if(props.userLoggedOutCb) {
                props.userLoggedOutCb();
            } else {
                setOpened(false);
                setRedirecting(true);
            }
        } catch(err) {
            console.error(err);
        }
    }

    if(redirecting) return <Redirect to="/"/>;

    return <div className={classes.loginInfoContainer}
                onMouseEnter={()=>setOpened(true)}
                onMouseLeave={trackedOut}>
        {
            userContext.profile ?
                <span className={classes.loginInfoText}>{userContext.profile.firstName} {userContext.profile.lastName}</span> :
                <span className={classes.loginInfoText}>Not logged in</span>
        }
        {
            userContext.profile ?
                <img className={classes.loginUserAvatar} src={userContext.profile.avatarUrl}/> :
                <img className={classes.loginUserAvatar} src="/public/images/nobody.png"/>
        }
        <div style={{width: "100%", display: opened ? "block" : "none"}}>
            <ul className={classes.discreetList}>
                <li onClick={doLogout}>Log out</li>
            </ul>
        </div>
    </div>

}

export default LoginStatusComponent;