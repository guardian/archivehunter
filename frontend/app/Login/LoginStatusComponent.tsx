import React from 'react';
import {Redirect} from "react-router-dom";
import axios from 'axios';
import {createStyles, makeStyles, withStyles} from "@material-ui/core";

interface LoginStatusComponentProps {
    userData: any;
    classes: Record<string, string>;
    userLoggedOutCb: ()=>void;
}

interface LoginStatusComponentState {
    opened?: boolean;
    redirecting?: boolean;
}

const styles = createStyles({
    loginInfoContainer: {
        float: "right",
        position: "absolute",
        top: 0,
        right: 0,
        padding: "0.4em",
        overflow: "hidden",
        cursor: "pointer"
    },
    loginUserAvatar: {
        height: "24px",
        borderRadius: "24px",
        verticalAlign: "middle",
    },
    loginInfoText: {
        color: "#AAAAAA",
        margin: "0.5em"
    },
    discreetList: {
        textAlign: "right",
        listStyle: "none"
    }
});

class LoginStatusComponent extends React.Component<LoginStatusComponentProps, LoginStatusComponentState> {
    classes: Record<string,string>;

    constructor(props:LoginStatusComponentProps){
        super(props);

        this.state = {
            opened: false,
            redirecting: false
        };

        this.classes = props.classes;
        this.trackedOut = this.trackedOut.bind(this);
        this.doLogout = this.doLogout.bind(this);
    }

    trackedOut(){
        window.setTimeout(()=>this.setState({opened: false}), 200);
    }

    doLogout(){
        axios.post("/api/logout").then(response=>{
            if(this.props.userLoggedOutCb) {
                this.props.userLoggedOutCb();
            } else {
                this.setState({opened: false, redirecting: true});
            }
        }).catch(err=>{
            console.error(err);
        })
    }
    render(){
        if(this.state.redirecting) return <Redirect to="/"/>;

        return <div className={this.classes.loginInfoContainer}
                    onMouseEnter={()=>this.setState({opened: true})}
                    onMouseLeave={this.trackedOut}>
            {
                this.props.userData ?
                    <span className={this.classes.loginInfoText}>{this.props.userData.firstName} {this.props.userData.lastName}</span> :
                    <span className={`${this.classes.loginInfoText}`}>Not logged in</span>
            }
            {
                this.props.userData ?
                    <img className={this.classes.loginUserAvatar} src={this.props.userData.avatarUrl}/> :
                    <img className={this.classes.loginUserAvatar} src="/public/images/nobody.png"/>
            }
            <div style={{width: "100%", display: this.state.opened ? "block" : "none"}}>
                <ul className={this.classes.discreetList}>
                    <li onClick={this.doLogout}>Log out</li>
                </ul>
            </div>
        </div>
    }
}

export default withStyles(styles)(LoginStatusComponent);