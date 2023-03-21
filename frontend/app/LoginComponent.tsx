import React, {useState, useEffect} from "react";
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
    errorText: {
        marginLeft: "1em",
        marginRight: "1em",
    },
}));

function generateCodeChallenge() {
    const array = new Uint8Array(32);
    crypto.getRandomValues(array);
    const str = array.reduce<string>((acc:string, x) => acc + x.toString(16).padStart(2, '0'), "");
    sessionStorage.setItem("cx", str);
    return str;
}

const LoginComponent = ()=>{
    const classes = useStyles();
    const [lastError, setLastError] = useState<string|undefined>(undefined);

    const doLogin = ()=>window.location.href = "/login?code_challenge=" + generateCodeChallenge();

    useEffect(() => {
        const dataToTest = new URLSearchParams(window.location.search).get("error");
        if (dataToTest) {
            setLastError(dataToTest)
        }
    }, []);

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
                {lastError ?
                    <>
                        <Grid item>
                            <Typography>
                                An error occurred when attempting to log you in.
                            </Typography>
                        </Grid>
                        <Grid item className={classes.errorText}>
                            <Typography>
                                {lastError}
                            </Typography>
                        </Grid>
                    </> : null}
            </Grid>
        </Paper>
    );
}

export default LoginComponent;