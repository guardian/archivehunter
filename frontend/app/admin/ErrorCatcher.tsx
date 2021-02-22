import React from "react";
import {createStyles, makeStyles, Theme, Typography, withStyles} from "@material-ui/core";
import {StyleRulesCallback} from "@material-ui/core/styles";

interface ErrorCatcherProps {
    classes:Record<string,string>;
    children?: React.ReactNode;
}

interface ErrorCatcherState {
    lastError?: string;
}

const styles = (theme:Theme)=>createStyles({
    errorText: {
        color: theme.palette.error.main
    }
});

/**
 * simple implementation of an error boundary, that should stop a misbehaving admin component from screwing up the
 * entire UI
 */
class ErrorCatcher extends React.Component<ErrorCatcherProps, ErrorCatcherState> {
    constructor(props:ErrorCatcherProps) {
        super(props);

        this.state = {
            lastError: undefined,
        }
    }

    componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
        console.error("An Admin page failed at ", errorInfo.componentStack);
        console.error("The error was ", error);
    }

    static getDerivedStateFromError(error:Error) {
        return {
            lastError: error.toString()
        }
    }

    render() {
        return this.state.lastError ? <Typography className={this.props.classes.errorText}>{this.state.lastError}</Typography> :
            this.props.children
    }
}

export default withStyles(styles)(ErrorCatcher);