import React from 'react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import {makeStyles} from "@material-ui/core";
import {baseStyles} from "./BaseStyles.ts";
import {RouteComponentProps} from "react-router";

const useStyles = makeStyles(Object.assign({
    loginIcon: {
        display: "inherit",
        //fontSize: "40px",
        lineHeight: "50px",
        marginLeft: "auto",
        marginRight: "auto",
        color: "white",
        width: "50px",
        height: "50px",
        textAlign: "center",
        verticalAlign: "bottom"
    }
}, baseStyles));

const FrontPage:React.FC<RouteComponentProps> = (props) => {
    const classes = useStyles();

    return <div className={classes.centered}>
        <h1 style={{textAlign: "center"}}>ArchiveHunter</h1>
        <span className={classes.loginIcon}> <FontAwesomeIcon className="login-icon" size="10x" icon="road"/></span>
        <p style={{textAlign: "center"}}>Work In Progress</p>
    </div>
}

export default FrontPage;