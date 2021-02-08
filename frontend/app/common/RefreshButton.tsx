import React from 'react';
import {IconButton, makeStyles} from "@material-ui/core";
import {Replay} from "@material-ui/icons";

interface RefreshButtonProps {
    isRunning: boolean;
    clickedCb: (evt:React.MouseEvent)=>void;
    showText?: boolean;
    caption?: string;
}

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