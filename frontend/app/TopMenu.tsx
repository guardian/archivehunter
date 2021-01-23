import React from 'react';
import { Link } from 'react-router-dom';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import {Grid, makeStyles} from "@material-ui/core";
import {baseStyles} from "./BaseStyles.ts";

interface TopMenuProps {
    visible: boolean;
    isAdmin: boolean;
}

const useStyles = makeStyles(Object.assign({
    menuContainer: {
        marginLeft: "auto",
        marginRight: "auto",
        width: "25%"
    },
    menuText: {
            color: "#AAA"
    }
},baseStyles));

const TopMenu:React.FC<TopMenuProps> = (props) => {
    if(!props.visible) return null;

    const classes = useStyles();

    const iconClassName = `${classes.smallIcon} ${classes.inlineIcon} ${classes.highlight}`
    return <Grid container justify="space-around" className={classes.menuContainer}>
        <Grid item>
            <Link className={classes.menuText}to="/search"><FontAwesomeIcon className={iconClassName} icon="search"/>Search</Link>
        </Grid>
        <Grid item>
            <Link className={classes.menuText} to="/browse"><FontAwesomeIcon className={iconClassName} icon="th-list"/>Browse</Link>
        </Grid>
        <Grid item>
            <Link className={classes.menuText} to="/lightbox"><FontAwesomeIcon className={iconClassName} icon="lightbulb"/>My Lightbox</Link>
        </Grid>
         <Grid item>
             <Link className={classes.menuText} to="/admin" style={{display: props.isAdmin ? "inline" : "none"}}><FontAwesomeIcon className={iconClassName} icon="wrench"/>Admin</Link>
         </Grid>
    </Grid>
}

export default TopMenu;