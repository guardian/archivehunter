import React from "react";
import {Button, Grid, makeStyles, Paper, Typography} from "@material-ui/core";
import clsx from "clsx";
import {ChevronRight} from "@material-ui/icons";

const useStyles = makeStyles((theme)=>({
    actionPanel: {
        width: "40%",
        maxWidth: "1000px",
    },
    panelContent: {
        padding: "1em",
    },
    bannerText: {
        textAlign: "center",
    },
    separated: {
        marginBottom: "1em",
    },
    loginBox: {
        marginLeft: "auto",
        marginRight: "auto",
        marginTop: "10em",
    },
}));

const LoginComponent = ()=>{
    const classes = useStyles();

    const doLogin = ()=>window.location.href = "/login";

    return (
        <Paper className={clsx(classes.actionPanel, classes.loginBox)}>
            <Grid container direction="column" alignItems="center" spacing={3}>
                <Grid item>
                    <Typography variant="h6" className={classes.bannerText}>
                        You need to log in to access the Multimedia production system, using
                        your normal Mac login credentials.
                    </Typography>
                </Grid>
                <Grid item>
                    <Button
                        style={{ marginLeft: "auto", marginRight: "auto" }}
                        variant="contained"
                        endIcon={<ChevronRight />}
                        onClick={doLogin}
                    >
                        Log me in
                    </Button>
                </Grid>
            </Grid>
        </Paper>
    );
}

export default LoginComponent;