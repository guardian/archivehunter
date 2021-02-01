import React from 'react';
import PropTypes from 'prop-types';
import {createStyles, Typography, withStyles} from "@material-ui/core";

const styles = (theme)=>createStyles({
    errorText: {
        color: theme.palette.error.dark
    }
});

class ErrorViewComponent extends React.Component {
    static propTypes = {
        error: PropTypes.object,
        brief: PropTypes.bool
    };

    /* expects axios error response in props.error */
    constructor(props){
        super(props);
    }

    niceMakeString(someObject){
        if(someObject.isObjectLike({})){
            return this.dictToList(someObject);
        } else if(Array.isArray(someObject)){
            return someObject.reduce((acc, item)=> acc+this.niceMakeString(item), "");
        } else {
            return someObject.toString();
        }

    }
    dictToList(dictObject){
        return <ul>
            {Object.keys(dictObject).map(key=>
                <li>{key}: {this.niceMakeString(dictObject[key])}</li>
            )}
        </ul>
    }

    static bestErrorString(errorObj, brief){
        if(brief) return "See console";
        if(errorObj.hasOwnProperty("detail")) return JSON.stringify(errorObj.detail);
        return errorObj.toString();
    }

    /**
     * return a string containing text that describes the given axios error.
     * @param error
     * @param brief
     * @returns {string}
     */
    static formatError(error, brief) {
        if (error.response) {
            // The request was made and the server responded with a status code
            // that falls out of the range of 2xx
            return `Server error ${error.response.status}: ` + ErrorViewComponent.bestErrorString(error.response.data, brief);
        } else if (error.request) {
            // The request was made but no response was received
            // `error.request` is an instance of XMLHttpRequest in the browser and an instance of
            // http.ClientRequest in node.js
            console.error("Failed request: ", error.request);
            return brief ? "No response" : "No response from server. See console log for more details."
        } else {
            // Something happened in setting up the request that triggered an Error
            console.error('Axios error setting up request: ', error.message);
            return brief ? "Couldn't send" : "Unable to set up request. See console log for more details."
        }
    }

    render(){
        if(!this.props.error){
            return <p className="error-text"/>
        }

        return <Typography className={this.props.classes.errorText}>
            {
                ErrorViewComponent.formatError(this.props.error, this.props.brief)
            }
        </Typography>
    }
}

export default withStyles(styles)(ErrorViewComponent);