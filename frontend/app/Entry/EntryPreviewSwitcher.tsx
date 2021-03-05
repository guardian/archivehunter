import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import {makeStyles} from "@material-ui/core";
import {baseStyles} from "../BaseStyles";
import clsx from "clsx";
import {ProxyType} from "../types";

interface  EntryPreviewSwitcherProps {
    availableTypes: string;
    typeSelected: (newType:ProxyType)=>void;
}

const expectedTypes = [
    "THUMBNAIL",
    "POSTER",
    "VIDEO",
    "AUDIO"
];

const useStyles = makeStyles(Object.assign({
    entryPreviewSwitcher: {
        width: "50%",
        display: "block",
        marginTop: "0.1em",
        marginBottom: "0.1em"
    },
    imageButtonEnabled :{
        paddingRight: "0.4em",
        cursor: "pointer"
    },
    imageButtonDisabled: {
        paddingRight: "0.4em",
        color: "lightgray"  //FIXME - this should be set by theme
    }
}, baseStyles));

const EntryPreviewSwitcher:React.FC<EntryPreviewSwitcherProps> = (props) => {
    const classes = useStyles();

    return <span className={clsx(classes.centered, classes.entryPreviewSwitcher)}>
        <FontAwesomeIcon icon="image"
                         size="4x"
                         className={props.availableTypes.includes("THUMBNAIL") ? classes.imageButtonEnabled : classes.imageButtonDisabled}
                         onClick={()=>props.typeSelected("THUMBNAIL")}/>
        <FontAwesomeIcon icon="film"
                         size="4x"
                         className={props.availableTypes.includes("VIDEO") ? classes.imageButtonEnabled : classes.imageButtonDisabled}
                         onClick={()=>props.typeSelected("VIDEO")}/>
        <FontAwesomeIcon icon="volume-up"
                         size="4x"
                         className={props.availableTypes.includes("AUDIO") ? classes.imageButtonEnabled : classes.imageButtonDisabled}
                         onClick={()=>props.typeSelected("AUDIO")}/>
    </span>
}

export default EntryPreviewSwitcher;