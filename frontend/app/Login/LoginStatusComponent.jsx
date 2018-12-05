import React from 'react';
import PropTypes from 'prop-types';
import {Redirect} from "react-router-dom";
import axios from 'axios';

class LoginStatusComponent extends React.Component {
    static propTypes = {
        userData: PropTypes.object,
        userLoggedOutCb: PropTypes.func
    };

    constructor(props){
        super(props);

        this.state = {
            opened: false,
            redirecting: false
        };

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

        return <div className="login-info-container"
                    onMouseEnter={()=>this.setState({opened: true})}
                    onMouseLeave={this.trackedOut}>
            {
                this.props.userData ?
                    <span className="login-info-text">{this.props.userData.firstName} {this.props.userData.lastName}</span> :
                    <span className="login-info-text not-loggedin">Not logged in</span>
            }
            {
                this.props.userData ?
                    <img className="login-user-avatar" src={this.props.userData.avatarUrl}/> :
                    <img className="login-user-avatar not-loggedin" src="/public/images/nobody.png"/>
            }
            <div style={{width: "100%", display: this.state.opened ? "block" : "none"}}>
                <ul style={{textAlign: "right"}}>
                    <li onClick={this.doLogout}>Log out</li>
                </ul>
            </div>
        </div>
    }
}

export default LoginStatusComponent;