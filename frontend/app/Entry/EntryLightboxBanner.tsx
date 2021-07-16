import React from 'react';
import PropTypes from 'prop-types';
import {LightboxIndex} from "../types";
import {Grid, IconButton, makeStyles, Toolbar, Tooltip} from "@material-ui/core";
import {Add, RemoveCircle} from "@material-ui/icons";

interface EntryLightboxBannerProps {
    lightboxEntries: LightboxIndex[];
    small?: boolean;
    removeFromLightbox?: ()=>void;
    putToLightbox?: ()=>void;
    isInLightbox?: boolean;
}

const useStyles = makeStyles({
    entryLightboxBanner: {
        width: "100%",
        marginLeft: "0.1em",
        marginRight: "0.1em",
    },
    entryLightboxBannerSmall: {
        height: "16px",
        marginRight: "0.2em",
        borderRadius: "16px"
    },
    entryLightboxBannerLarge: {
        height: "26px",
        marginRight: "0.4em",
        marginBottom: "0.6em",
        borderRadius: "32px"
    }
});

const EntryLightboxBanner:React.FC<EntryLightboxBannerProps> = (props)=> {
    const classes = useStyles();

    return <Grid container className={classes.entryLightboxBanner} alignItems="flex-end" justify="center">
        {
            props.removeFromLightbox && props.putToLightbox ?
                <Grid item>
                    <IconButton onClick={() => props.isInLightbox && props.removeFromLightbox  ? props.removeFromLightbox() : props.putToLightbox ? props.putToLightbox() : false}>
                    {
                        props.isInLightbox ? <Tooltip title="Remove from my lightbox">
                                <RemoveCircle/></Tooltip>
                            :
                            <Tooltip title="Add to my lightbox">
                                <Add/>
                            </Tooltip>
                    }
                </IconButton>
                </Grid>: null
        }
        {
            props.lightboxEntries.map(entry=>
                <Grid item>
                    <Tooltip title={entry.owner}>
                        <img src={entry.avatarUrl ? entry.avatarUrl : "/static/default-avatar.png"}
                         alt={entry.owner}
                         className={props.small ? classes.entryLightboxBannerSmall : classes.entryLightboxBannerLarge }/>
                    </Tooltip>
                </Grid>
            )
        }
    </Grid>
}

export default EntryLightboxBanner;