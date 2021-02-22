import React from 'react';
import PropTypes from 'prop-types';
import {Redirect} from "react-router-dom";
import {createStyles, withStyles, Paper, Typography} from "@material-ui/core";

const styles = createStyles({
    container: {
        padding: "1em"
    },
    centered: {
        marginLeft: "auto",
        marginRight: "auto"
    }
});

class ConfirmationComponent extends React.Component {
    static propTypes = {
        searchMode:PropTypes.string,
        selectedDeployment: PropTypes.string,
        manualInput: PropTypes.object
    };

    render(){
        return <>
            <Typography variant="h5">Confirmation</Typography>
            <Paper elevation={3} className={this.props.classes.container}>
            {
                this.props.searchMode==="search" ?
                    <Typography className={this.props.classes.centered}>
                        The existing Cloudformation stack {this.props.selectedDeployment} will be connected to this Archive Hunter instance.
                    </Typography> :
                    <p>Archive Hunter will attempt to contact the topics:
                        <ul>
                            <li>{this.props.manualInput ? this.props.manualInput.inputTopic : "(none)"}</li>
                            <li>{this.props.manualInput ? this.props.manualInput.replyTopic : "(none)"}</li>
                            <li>{this.props.manualInput ? this.props.manualInput.managementRole : "(none)"}</li>
                        </ul>
                    </p>
            }
        </Paper>
            </>
    }
}

export default withStyles(styles)(ConfirmationComponent);