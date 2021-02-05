import React from 'react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import PropTypes from 'prop-types';
import {IconButton, makeStyles} from "@material-ui/core";
import {Replay} from "@material-ui/icons";

interface RefreshButtonProps {
    isRunning: boolean;
    clickedCb: (evt:React.MouseEvent)=>void;
    showText?: boolean;
    caption?: string;
}

/*
.spin {
    -webkit-transition-duration: 0.6s;
    -moz-transition-duration: 0.6s;
    -ms-transition-duration: 0.6s;
    -o-transition-duration: 0.6s;
    transition-duration: 0.6s;
    -webkit-animation:spin 1s linear infinite;
    -moz-animation:spin 1s linear infinite;
    animation:spin 1s linear infinite;
}

@-moz-keyframes spin { 100% { -moz-transform: rotate(360deg); } }
@-webkit-keyframes spin { 100% { -webkit-transform: rotate(360deg); } }
@keyframes spin { 100% { -webkit-transform: rotate(360deg); transform:rotate(360deg); } }
 */
const useStyles = makeStyles((theme)=>({
    spin: {
        transitionDuration: "0.6s",
        animation: `$spin 1s linear infinite`
    },
    "@keyframes spin": {
        "100%": {
            transform: "rotate(-360deg)"
        }
    }
}));

const RefreshButton:React.FC<RefreshButtonProps> = (props) => {
    const classes = useStyles();

    return <IconButton className="clickable" onClick={props.clickedCb}>
        <Replay className={props.isRunning ? classes.spin : ""}/>
        {
            props.showText ?
                props.caption ? props.caption : "Refresh"
                : ""
        }
    </IconButton>
}

export default RefreshButton;